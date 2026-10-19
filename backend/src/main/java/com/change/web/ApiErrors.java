package com.change.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 把「调用方弄错了」翻译成**同一种形状** `{error: "给人看的一句话"}`。
 *
 * <p>不加第一层的话，服务里抛的 {@link IllegalArgumentException}（日期为空、动作没有组……）
 * 会被 Spring 默认映射成 **500**——对调用方来说是「服务器坏了」，而实际是「你传的东西不对」。
 * 前端拿到 400 才知道该提示用户改输入。
 *
 * <p>{@link ResponseStatusException} 那一条是后来补的，起因是**真机发现**：
 * `TrainingController` 找不到某次训练时抛的是它，而 Spring 默认的错误体是
 * `{"timestamp":…,"status":404,"error":"Not Found","path":…}`——**我们在 reason 里写的那句
 * 中文根本没有出现**（默认 `server.error.include-message=never`），前端只能显示英文的
 * `Not Found`。这里把它收进同一套形状，前端读 `error` 的代码一行都不用改。
 *
 * <p>（没有选 `server.error.include-message=always`：那会让 404 的正文长成 Spring 的字段
 * 布局、和 400 的 `{error}` 不一致，前端还得同时认两种形状。）
 *
 * <p>其余异常仍然按 500 暴露（它们的栈是我们需要看到的）。
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

    /**
     * 带状态码的显式异常（目前只有「找不到某次训练」这一处）。
     *
     * <p>**状态码用原异常带的那个**（404 就得是 404，不能被 advice 统一成 400 或 200）；
     * 只有正文换成我们的 `{error}` 形状。`reason` 为 null 时退到状态码本身，
     * 免得正文里出现一个 `null`。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> statusException(ResponseStatusException e) {
        String reason = e.getReason();
        Map<String, Object> body = Map.of(
                "error", reason == null ? e.getStatusCode().toString() : reason);
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }
}
