package com.heatroute.api;

import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler({IOException.class, IllegalArgumentException.class})
  public ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.unprocessableEntity()
        .body(Map.of("detail", String.valueOf(e.getMessage())));
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<?> missing(Exception e) {
    return ResponseEntity.status(404).body(Map.of("detail", "Calculation not found"));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<?> conflict(Exception e) {
    return ResponseEntity.status(409).body(Map.of("detail", String.valueOf(e.getMessage())));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<?> limit() {
    return ResponseEntity.status(413).body(Map.of("detail", "File exceeds 3 GiB"));
  }
}
