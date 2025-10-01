package com.buhmwoo.oneask.common.dto;

public enum ErrorCode {
  VALIDATION_ERROR("E400"),
  BAD_REQUEST("E400"),
  UNAUTHORIZED("E401"),
  FORBIDDEN("E403"),
  NOT_FOUND("E404"),
  CONFLICT("E409"),
  SQL_ERROR("E500-SQL"),
  INTERNAL_ERROR("E500");

  private final String code;
  ErrorCode(String code) { this.code = code; }
  public String getCode() { return code; }
}
