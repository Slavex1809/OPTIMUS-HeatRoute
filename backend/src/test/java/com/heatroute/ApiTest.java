package com.heatroute;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"optimus.storage=target/test-jobs", "spring.profiles.active=local"})
@AutoConfigureMockMvc
class ApiTest {
  @Autowired MockMvc mvc;

  @Test
  void healthUiAndOpenApi() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mvc.perform(get("/api/v1/capabilities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reconstruction").value(false));
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("OPTIMUS HeatRoute"));
    mvc.perform(get("/index.html")).andExpect(status().isOk());
  }

  @Test
  void missingJobIs404() throws Exception {
    mvc.perform(get("/api/v1/calculations/00000000-0000-0000-0000-000000000000"))
        .andExpect(status().isNotFound());
  }
  @Test
  void importReturnsAuditAndByteFingerprint() throws Exception {
    byte[] bytes=getClass().getResourceAsStream("/static/data/corrected.geojson").readAllBytes();
    StringBuilder expected=new StringBuilder();
    for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) expected.append(String.format("%02x",b));
    var file=new org.springframework.mock.web.MockMultipartFile("file","dataset.geojson","application/geo+json",bytes);
    mvc.perform(multipart("/api/v1/calculations").file(file))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.audit.valid").value(true))
      .andExpect(jsonPath("$.audit.featureCount").value(144))
      .andExpect(jsonPath("$.audit.objectTypes.oks_connection_point").value(17))
      .andExpect(jsonPath("$.inputSha256").value(expected.toString()));
  }
}
