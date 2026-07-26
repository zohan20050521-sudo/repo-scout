package io.github.chada010.reposcout.exception;

/**
 * 请求参数不合法,由全局异常处理映射为 400 + INVALID_PARAM。
 * message 要求对用户可读、可行动。
 */
public class InvalidParamException extends RuntimeException {

    public InvalidParamException(String message) {
        super(message);
    }
}
