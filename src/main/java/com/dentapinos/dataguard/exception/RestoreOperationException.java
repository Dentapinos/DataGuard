package com.dentapinos.dataguard.exception;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RestoreOperationException extends RuntimeException {

    private final String table;
    private final String sqlState;
    private final int errorCode;
    private final String sqlMessage;

    public RestoreOperationException(String message,
                                     String table,
                                     String sqlState,
                                     int errorCode,
                                     String sqlMessage,
                                     Throwable cause) {
        super(message, cause);
        this.table = table;
        this.sqlState = sqlState;
        this.errorCode = errorCode;
        this.sqlMessage = sqlMessage;
    }
}
