package com.bracit.fms.fms.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThumbnailResponse {
    private String fileName;
    private String contentType;
    private byte[] content;
    private long size;
}