package com.change.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把校验失败翻译成 400。
 *
 * <p>不加这一层的话，服务里抛的 {@link IllegalArgumentException}（日期为空、动作没有组……）
 * 会被 Spring 默认映射成 **500**——对调用方来说是「服务器坏了」，而实际是「你传的东西不对」。
 * 前端拿到 400 才知道该提示用户改输入。
 *
 * <p>只映射这一种异常：其余异常仍然按 500 暴露（它们的栈是我们需要看到的）。
 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "请求不合法" : e.getMessage();
        return Map.of("error", message);
    }

    /** 参数类型/格式错（如日期写成 `2026/10/14`）——它不是 IllegalArgumentException 的子类。 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badParameter(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return Map.of("error", "参数 " + e.getName() + " 格式不对");
    }
}
