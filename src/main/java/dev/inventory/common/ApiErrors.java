package dev.inventory.common;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    MethodArgumentTypeMismatchException.class,
    ConstraintViolationException.class
  })
  ProblemDetail invalid(Exception exception) {
    var problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "Invalid request payload, parameter or header");
    problem.setProperty("code", "INVALID_REQUEST");
    return problem;
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail conflict(Exception exception) {
    var problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "Request conflicts with existing data");
    problem.setProperty("code", "DATA_CONFLICT");
    return problem;
  }

  @ExceptionHandler(PessimisticLockingFailureException.class)
  ProblemDetail contention(Exception exception) {
    var problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Resource is busy; retry with the same idempotency key");
    problem.setProperty("code", "RETRY_LATER");
    return problem;
  }

  @ExceptionHandler(ApiException.class)
  ProblemDetail handle(ApiException exception) {
    var problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
    problem.setProperty("code", exception.code());
    return problem;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(MethodArgumentNotValidException exception) {
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    problem.setProperty("code", "INVALID_REQUEST");
    problem.setProperty(
        "errors",
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList());
    return problem;
  }
}
