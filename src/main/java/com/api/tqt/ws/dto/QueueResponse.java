package com.api.tqt.ws.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class QueueResponse {
    private String userId;
    private Long rank;
    private String status;
    private String token;
}
