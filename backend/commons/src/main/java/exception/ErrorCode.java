package exception;

public enum ErrorCode {
USER_NOT_FOUND(1001),
INVALID_REQUEST(1002),
INTERNAL_SERVER_ERROR(5000);

private final int code;

ErrorCode(int code) {
    this.code = code;
}

public int getCode() {
    return code;
}
}
