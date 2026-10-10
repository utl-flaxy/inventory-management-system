package com.example.demo.service;

import com.example.demo.dto.InventoryAdjustmentRequest;
import com.example.demo.dto.InventoryMovementResponse;
import com.example.demo.entity.*;
import com.example.demo.exception.InvalidInventoryAdjustmentException;
import com.example.demo.exception.ProductNotFoundException;
import com.example.demo.exception.IdempotencyConflictException;
import com.example.demo.exception.InvalidIdempotencyKeyException;
import com.example.demo.repository.InventoryMovementRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.sql.SQLException;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class InventoryService {
    private static final Pattern DUPLICATE_KEY = Pattern.compile("for key '([^']+)'$");
    private static final String UNIQUE_KEY = "uk_inventory_movements_actor_idempotency_key";
    private final ProductRepository products;
    private final InventoryMovementRepository movements;
    private final CurrentUser currentUser;
    private final EntityManager entityManager;
    private final Clock clock;
    private final UserRepository users;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InventoryMovementResponse adjust(Long id, InventoryAdjustmentRequest request, String idempotencyKey) {
        User authenticated = requireAdmin();
        if (id == null || request == null || request.getDelta() == null || request.getDelta() == 0 || request.getReason() == null
                || (request.getNote() != null && request.getNote().length() > 500))
            throw new InvalidInventoryAdjustmentException();
        try {
            IdempotencyKey.validate(idempotencyKey);
        } catch (IllegalArgumentException ex) {
            throw new InvalidIdempotencyKeyException(ex.getMessage());
        }
        String fingerprint = InventoryAdjustmentFingerprint.fromValidatedRequest(id, request);
        // 注文createと同じUser -> Product順。同actorのkey判定をstockに触れる前に直列化する。
        User actor = users.findByIdForUpdate(authenticated.getId())
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("認証ユーザーが存在しません"));
        var existing = movements.findByActorIdAndIdempotencyKey(actor.getId(), idempotencyKey);
        if (existing.isPresent()) {
            if (!fingerprint.equals(existing.get().getRequestFingerprint()))
                throw IdempotencyConflictException.inventoryAdjustment(null);
            return InventoryMovementResponse.from(existing.get());
        }
        Product product = products.findByIdForUpdate(id).orElseThrow(ProductNotFoundException::new);
        entityManager.refresh(product, LockModeType.PESSIMISTIC_WRITE);
        int before = product.getStock();
        product.adjustStock(request.getDelta());
        InventoryMovement movement = InventoryMovement.createManual(product, request.getDelta(), before, product.getStock(),
                request.getReason().movementReason(), actor, request.getNote(), clock.instant(), idempotencyKey, fingerprint);
        try {
            products.save(product);
            InventoryMovement saved = movements.save(movement);
            products.flush();
            return InventoryMovementResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            if (isIdempotencyUniqueViolation(ex)) throw IdempotencyConflictException.inventoryAdjustment(ex);
            // rollback-only Transaction内では再検索/replayしない。他制約はsafe500のまま。
            throw ex;
        }
    }

    private boolean isIdempotencyUniqueViolation(DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062 && sql.getMessage() != null) {
                var key = DUPLICATE_KEY.matcher(sql.getMessage());
                if (key.find() && UNIQUE_KEY.equals(key.group(1))) return true;
            }
        }
        return false;
    }

    // OrderServiceが同じProductロック下で適用した差分を、そのbusiness transactionへ追記する。
    // 単独commitやREQUIRES_NEWを許可しない。
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOrderChange(Product product, int before, int delta, Order order,
                                  InventoryMovementReason reason, User actor) {
        movements.save(InventoryMovement.create(product, delta, before, product.getStock(), reason,
                InventorySourceType.ORDER, order.getId(), actor, null, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Page<InventoryMovementResponse> history(Long id, Pageable pageable) {
        requireAdmin();
        if (!products.existsById(id)) throw new ProductNotFoundException();
        Pageable fixed = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return movements.findByProductId(id, fixed).map(InventoryMovementResponse::from);
    }

    private User requireAdmin() {
        User user = currentUser.requireUser();
        if (!"ROLE_ADMIN".equals(user.getRole())) throw new AccessDeniedException("この操作を実行する権限がありません");
        return user;
    }
}
