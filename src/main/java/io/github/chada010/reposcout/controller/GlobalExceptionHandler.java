package io.github.chada010.reposcout.controller;

import java.util.stream.Collectors;

import dev.langchain4j.exception.LangChain4jException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.github.chada010.reposcout.controller.dto.ErrorResponse;
import io.github.chada010.reposcout.exception.GithubUnavailableException;
import io.github.chada010.reposcout.exception.InvalidParamException;
import io.github.chada010.reposcout.exception.RepoNotFoundException;

/**
 * 全局异常处理:统一错误结构 {code, message}。
 * message 对用户可读、可行动,不含堆栈、密钥与内部类名。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidParamException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidParam(InvalidParamException e) {
        return new ErrorResponse("INVALID_PARAM", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return new ErrorResponse("INVALID_PARAM", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnreadable(HttpMessageNotReadableException e) {
        return new ErrorResponse("INVALID_PARAM", "请求体不是合法 JSON,请检查格式");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        // 如 GET /api/repos/abc:路径参数类型不匹配也走统一错误结构,不落成 500
        return new ErrorResponse("INVALID_PARAM", "参数 " + e.getName() + " 类型不合法");
    }

    @ExceptionHandler(RepoNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleRepoNotFound(RepoNotFoundException e) {
        return new ErrorResponse("REPO_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(GithubUnavailableException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ErrorResponse handleGithubUnavailable(GithubUnavailableException e) {
        // 失败上下文(path、状态码)已由 GithubApiClient 记 WARN,此处不重复
        return new ErrorResponse("GITHUB_UNAVAILABLE", e.getMessage());
    }

    @ExceptionHandler(LangChain4jException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ErrorResponse handleLlmFailure(LangChain4jException e) {
        // 只记异常摘要,不打印请求内容;密钥不会出现在 langchain4j 异常信息中
        log.error("DeepSeek 调用失败: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        return new ErrorResponse("LLM_UNAVAILABLE",
                "AI 服务暂时不可用(上游模型超时、限流或鉴权失败),请稍后重试;若持续失败请检查 DEEPSEEK_API_KEY 等配置");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return new ErrorResponse("INTERNAL_ERROR", "服务器内部错误,请稍后重试;若持续出现请联系维护者并附上时间点");
    }
}
