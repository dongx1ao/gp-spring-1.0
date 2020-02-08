package com.peter.spring.demo.service.impl;

import com.peter.spring.demo.service.IDemoService;
import com.peter.spring.mvnframework.annotation.GPService;

/**
 * 核心业务逻辑
 */
@GPService
public class DemoService implements IDemoService {
    @Override
    public String get(String name) {
        return "My name is " + name;
    }
}
