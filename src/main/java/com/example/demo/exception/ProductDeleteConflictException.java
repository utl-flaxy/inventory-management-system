package com.example.demo.exception;

public class ProductDeleteConflictException extends RuntimeException {
    public ProductDeleteConflictException() {
        super("注文履歴または在庫履歴から参照されているため商品を削除できません");
    }
}
