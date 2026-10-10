package com.example.demo.dto;

import lombok.Getter;
import lombok.Setter;
import com.example.demo.validation.Utf8Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "ユーザー名は必須です")
    @Size(min = 3, max = 50, message = "ユーザー名は3〜50文字で入力してください")
    @Pattern(regexp = "[A-Za-z0-9._-]{3,50}", message = "ユーザー名は半角英数字と._-で入力してください")
    private String username;

    @NotBlank(message = "パスワードは必須です")
    @Size(min = 8, max = 72, message = "パスワードは8〜72文字で入力してください")
    @Utf8Size(max = 72, message = "パスワードはUTF-8で72 bytes以内で入力してください")
    @Schema(description = "8〜72文字、UTF-8で72 bytes以下。trimしない。", format = "password",
            accessMode = Schema.AccessMode.WRITE_ONLY)
    private String password;

}
