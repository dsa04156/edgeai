package io.edgeai.app.controller;

import io.edgeai.app.config.SecurityConfiguration;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.ManagedDeviceService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=ManagedDeviceController.class)
@Import(SecurityConfiguration.class)
class ManagedDeviceControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ManagedDeviceService service;
    @Test void newManagementMutationsRequireCsrf() throws Exception {
        for(String path:List.of("/api/devices","/api/devices/sensor/properties","/api/device-profiles","/api/device-connections/test"))mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/devices/sensor/properties/temperature").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/devices/sensor").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/devices/sensor")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/devices/sensor/properties/temperature")).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void controlledCreateConflictAndInvalidProfileArePublicErrors() throws Exception {
        when(service.create("{}")).thenThrow(new IllegalArgumentException("private parser detail"));
        mvc.perform(post("/api/devices").with(csrf()).contentType("application/json").content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DEVICE_PROFILE"));
        when(service.get("sensor")).thenThrow(new ControlPlaneException(404,"DEVICE_RESOURCE_ERROR","장치가 없습니다."));
        mvc.perform(get("/api/devices/sensor")).andExpect(status().isNotFound());
    }
    @Test void validRegistrationUsesCreatedAndDeletionUsesNoContent() throws Exception {
        when(service.create("{}")).thenReturn(Map.of("id","sensor","state","UNKNOWN"));
        mvc.perform(post("/api/devices").with(csrf()).contentType("application/json").content("{}")).andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("UNKNOWN"));
        mvc.perform(delete("/api/devices/sensor").with(csrf())).andExpect(status().isNoContent());verify(service).delete("sensor");
        mvc.perform(delete("/api/devices/sensor/properties/temperature").with(csrf())).andExpect(status().isNoContent());verify(service).deleteProperty("sensor","temperature");
    }
    @Test void updatingOnePropertyUsesItsOwnPutRoute() throws Exception {
        when(service.updateProperty("sensor","temperature","{}")).thenReturn(Map.of("id","sensor","state","ONLINE"));
        mvc.perform(put("/api/devices/sensor/properties/temperature").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("sensor"));
        verify(service).updateProperty("sensor","temperature","{}");
        when(service.updateProperty("sensor","temperature","{}")).thenThrow(new IllegalArgumentException("Property name cannot change"));
        mvc.perform(put("/api/devices/sensor/properties/temperature").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DEVICE_PROFILE"));
        doThrow(new ControlPlaneException(409,"DEVICE_RESOURCE_ERROR","동시 변경 충돌")).when(service).updateProperty("sensor","temperature","{}");
        mvc.perform(put("/api/devices/sensor/properties/temperature").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_RESOURCE_ERROR"));
        when(service.updateProperty("sensor","missing","{}")).thenThrow(new ControlPlaneException(404,"DEVICE_PROPERTY_NOT_FOUND","측정 항목 없음"));
        mvc.perform(put("/api/devices/sensor/properties/missing").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DEVICE_PROPERTY_NOT_FOUND"));
    }
    @Test void addingAnIndividualPropertyReturnsTheUpdatedDeviceAndReportsConflicts() throws Exception {
        when(service.addProperty("sensor","{}")).thenReturn(Map.of("id","sensor","state","ONLINE"));
        mvc.perform(post("/api/devices/sensor/properties").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("sensor"));
        when(service.addProperty("sensor","{}")).thenThrow(new ControlPlaneException(409,"DEVICE_PROPERTY_ALREADY_EXISTS","이미 등록된 측정 항목입니다."));
        mvc.perform(post("/api/devices/sensor/properties").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_PROPERTY_ALREADY_EXISTS"));
    }
}
