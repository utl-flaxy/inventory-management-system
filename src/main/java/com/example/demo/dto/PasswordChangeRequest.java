package com.example.demo.dto;

import com.example.demo.validation.Utf8Size;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Credentials are write-only and deliberately have no generated toString. */
@Getter
@Setter
public class PasswordChangeRequest {
    @NotBlank(message = "パスワードは必須です")
    @Size(min = 8, max = 72, message = "パスワードは8〜72文字で入力してください")
    @Utf8Size(max = 72, message = "パスワードはUTF-8で72 bytes以内で入力してください")
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
            description = "現password。loginと同じ検証。trimしない。", minLength = 8, maxLength = 72)
    private String currentPassword;

    @NotBlank(message = "パスワードは必須です")
    @Size(min = 8, max = 72, message = "パスワードは8〜72文字で入力してください")
    @Utf8Size(max = 72, message = "パスワードはUTF-8で72 bytes以内で入力してください")
    @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
            description = "新password。USER最低8文字、ADMIN最低16文字・制御文字禁止。UTF-8最大72 bytes、trimしない。",
            minLength = 8, maxLength = 72)
    private String newPassword;
}
