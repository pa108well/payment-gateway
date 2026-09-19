package dev.gateway.exception;


import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;

@Getter
@FieldDefaults(level = lombok.AccessLevel.PRIVATE, makeFinal = true)
public class ApiException extends RuntimeException {

    HttpStatus status;

    String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment was not found");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }


}
