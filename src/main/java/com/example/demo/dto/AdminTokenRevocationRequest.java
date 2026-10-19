package com.example.demo.dto;

import com.example.demo.validation.Utf8Size;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** The acting administrator's credential, never the target's. No credential-bearing toString. */
@Getter
@Setter
public class AdminTokenRevocationRequest {
    @NotBlank(message = "パスワードは必須です")
    @Size(min = 8, max = 72, message = "パスワードは8〜72文字で入力してください")
    @Utf8Size(max = 72, message = "パスワードはUTF-8で72 bytes以内で入力してください")
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY, minLength = 8, maxLength = 72,
            description = "操作ADMIN本人の現password。loginと同じ検証、trimしない。対象ユーザーのpasswordではない。")
    private String currentPassword;
}
