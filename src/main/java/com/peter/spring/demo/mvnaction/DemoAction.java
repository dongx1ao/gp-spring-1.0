package com.peter.spring.demo.mvnaction;

import com.peter.spring.demo.service.IDemoService;
import com.peter.spring.mvnframework.annotation.GPAutowired;
import com.peter.spring.mvnframework.annotation.GPController;
import com.peter.spring.mvnframework.annotation.GPRequestMapping;
import com.peter.spring.mvnframework.annotation.GPRequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@GPController
@GPRequestMapping("/demo")
public class DemoAction {

    @GPAutowired
    private IDemoService demoService;

    @GPRequestMapping("/query")
    public void query(HttpServletRequest request, HttpServletResponse response, @GPRequestParam("name") String name){
        String result = demoService.get(name);

        try {
            response.getWriter().write(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @GPRequestMapping("/add")
    public void add(HttpServletRequest request,HttpServletResponse response,@GPRequestParam("a") Integer a,@GPRequestParam("b") Integer b){

        try {
            response.getWriter().write(a + "+" + b + "=" + (a+b));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

