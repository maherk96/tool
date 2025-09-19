package com.example.springload.clients;

import lombok.Data;

import java.util.Map;

@Data
  public  class Request {
    private HttpMethod method;
    private String path;
    private Map<String, String> headers;
    private Map<String, String> query;
    private Object body;
  }
