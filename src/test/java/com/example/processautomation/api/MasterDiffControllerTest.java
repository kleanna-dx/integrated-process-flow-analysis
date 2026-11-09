package com.example.processautomation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.processautomation.baseline.BaselineRegistrationService;
import com.example.processautomation.change.ChangeDetectionResult;
import com.example.processautomation.change.ChangeDetectionService;
import com.example.processautomation.excel.OoxmlPackageInspector;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(SpringExtension.class)
class MasterDiffControllerTest {
    private final BaselineRegistrationService baselineService = org.mockito.Mockito.mock(BaselineRegistrationService.class);
    private final ChangeDetectionService changeService = org.mockito.Mockito.mock(ChangeDetectionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BaselineController controller = new BaselineController(baselineService, changeService,
                org.mockito.Mockito.mock(OoxmlPackageInspector.class), new ApiAccessGuard("test-token"));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void acceptsExactlyOneMasterFileAndReturnsPreviewJson() throws Exception {
        byte[] bytes = new byte[]{'P', 'K', 3, 4};
        when(changeService.compare(any(byte[].class), eq("updated.xlsx"))).thenReturn(
                new ChangeDetectionResult(77L, "V1.0", "TEST_ONLY", "updated.xlsx", "abc", 1332,
                        1332, 0, 0, 0, 0, 0, false, "NO_CHANGED_EQUIPMENT", false,
                        List.of(), List.of(), List.of(), List.of()));
        MockMultipartFile master = new MockMultipartFile("masterFile", "updated.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);

        mvc.perform(multipart("/api/admin/master-diff").file(master).header("X-Admin-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeRunId").value(77))
                .andExpect(jsonPath("$.analysisMode").value("TEST_ONLY"))
                .andExpect(jsonPath("$.baselineRecordCount").value(1332));
        verify(changeService).compare(bytes, "updated.xlsx");
    }

    @Test
    void rejectsUnauthenticatedUploadBeforeReadingOrComparingFile() throws Exception {
        MockMultipartFile master = new MockMultipartFile("masterFile", "updated.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{'P', 'K', 3, 4});

        mvc.perform(multipart("/api/admin/master-diff").file(master))
                .andExpect(status().isUnauthorized());
        verify(changeService, never()).compare(any(byte[].class), any());
    }

    @Test
    void rejectsNonXlsxUploadBeforeComparison() throws Exception {
        MockMultipartFile master = new MockMultipartFile("masterFile", "updated.csv",
                MediaType.TEXT_PLAIN_VALUE, "not excel".getBytes());

        mvc.perform(multipart("/api/admin/master-diff").file(master).header("X-Admin-Token", "test-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only .xlsx files are accepted: updated.csv"));
        verify(changeService, never()).compare(any(byte[].class), any());
    }
}
