package com.laa66.spatial.app.api;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.BDDMockito.given;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laa66.spatial.app.dto.LocationPointDto;
import com.laa66.spatial.app.dto.SearchNearbyRequest;
import com.laa66.spatial.app.mapper.LocationDtoMapper;
import com.laa66.spatial.app.service.SpatialService;
import com.laa66.spatial.domain.model.LocationPoint;

@WebMvcTest(SpatialController.class)
@Import({LocationDtoMapper.class, ObjectMapper.class})
class SpatialControllerUnitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SpatialService spatialService;

    @Test
    @DisplayName("Should return nearby locations and verify parsed response")
    void shouldReturnNearbyLocations() throws Exception {
        // given
        SearchNearbyRequest request = new SearchNearbyRequest(17.1, 51.1, 1000.0);
        LocationPoint model = new LocationPoint("Park", 17.1, 51.1);
        
        given(spatialService.findNearby(anyDouble(), anyDouble(), anyDouble()))
                .willReturn(List.of(model));

        // when
        MvcResult result = mockMvc.perform(get("/nearby")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        String contentAsString = result.getResponse().getContentAsString();
        List<LocationPointDto> response = objectMapper.readValue(contentAsString, new TypeReference<>() {});

        // then
        assertThat(response).hasSize(1);
        LocationPointDto actual = response.get(0);
        
        assertThat(actual.getName()).isEqualTo("Park");
        assertThat(actual.getLongitude()).isEqualTo(17.1);
        assertThat(actual.getLatitude()).isEqualTo(51.1);
    }
}