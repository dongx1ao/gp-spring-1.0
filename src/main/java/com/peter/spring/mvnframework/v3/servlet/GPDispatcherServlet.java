package com.peter.spring.mvnframework.v3.servlet;

import com.peter.spring.mvnframework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class GPDispatcherServlet extends HttpServlet {

    private static final String LOCATION = "contextConfigLocation";

    private Properties p = new Properties();

    private List<String> classNames = new ArrayList<String>();

    private Map<String,Object> ioc = new HashMap<String, Object>();

    //保存所有的url和方法的映射关系
    private List<Handler> handlerMapping = new ArrayList<Handler>();

    public GPDispatcherServlet() {
        super();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req,resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            //匹配到对应的方法
            doDispatch(req,resp);
        } catch (Exception e) {
            resp.getWriter().write("500 Exception,Details:\r\n" + Arrays.toString(e.getStackTrace()).replaceAll("\\[|\\]", "").replaceAll(",\\s", "\r\n"));
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) {

        Handler handler = getHandler(req);

        try {
            if (handler == null){
                resp.getWriter().write("404 Not Found");
            }

            Class<?>[] paramTypes = handler.method.getParameterTypes();
            //保存所有需要自动赋值的参数值
            Object[] paramValues = new Object[paramTypes.length];

            Map<String,String[]> params = req.getParameterMap();

            for (Map.Entry<String, String[]> param : params.entrySet()) {
                String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]", "").replaceAll(",\\s", ",");

                //如果找到匹配的对象，则开始填充参数值
                if(!handler.paramIndexMapping.containsKey(param.getKey())){continue;}
                int index = handler.paramIndexMapping.get(param.getKey());
                paramValues[index] = convert(paramTypes[index],value);
            }


            //设置方法中的request和response对象
            int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[reqIndex] = req;
            int respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[respIndex] = resp;

            handler.method.invoke(handler.controller, paramValues);


        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //url传过来的参数都是String类型的，HTTP是基于字符串协议
    //只需要把String转换为任意类型就好
    private Object convert(Class<?> type,String value){
        if(Integer.class == type){
            return Integer.valueOf(value);
        }
        //如果还有double或者其他类型，继续加if
        //这时候，我们应该想到策略模式了
        //在这里暂时不实现，希望小伙伴自己来实现
        return value;
    }

    private Handler getHandler(HttpServletRequest req) {
        if (handlerMapping.isEmpty()){return null;}

        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace(contextPath, "").replaceAll("/+", "/");

        for (Handler handler: handlerMapping
             ) {
            Matcher matcher = handler.pattern.matcher(url);
            //如果没有匹配上继续下一个匹配
            if (!matcher.matches()){continue;}
            return handler;
        }

        return  null;
    }


    /**
     * 初始化 加载配置文件
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
        //1、加载配置文件
        doLoadConfig(config.getInitParameter(LOCATION));

        //2、扫描所有相关的类
        doScanner(p.getProperty("scanPackage"));

        //3、初始化所有相关类的实例，并保存到IOC容器中
        doInstance();

        //4、依赖注入
        doAutowired();

        //5、构造HandlerMapping 将url和Method一一对应
        initHandlerMapping();

        System.out.println("mvc framework is init");
    }

    private void initHandlerMapping() {
        if (ioc.isEmpty()){return;}

        for (Map.Entry<String,Object> entry: ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if (!clazz.isAnnotationPresent(GPController.class)){continue;}

            String url = "";
            //获得Controller的url配置
            if (clazz.isAnnotationPresent(GPRequestMapping.class)){
                GPRequestMapping requestMapping = clazz.getAnnotation(GPRequestMapping.class);
                url = requestMapping.value();
            }

            //获得Method的url配置
            Method[] methods = clazz.getMethods();

            for (Method method : methods) {
                if (!method.isAnnotationPresent(GPRequestMapping.class)){continue;}

                //映射URL
                GPRequestMapping requestMapping = method.getAnnotation(GPRequestMapping.class);
                String regex = ("/" + url + requestMapping.value()).replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(regex);
                handlerMapping.add(new Handler(pattern,entry.getValue(),method));

                System.out.println("mapping"+regex+","+method);
            }
        }
    }

    private void doAutowired() {
        if (ioc.isEmpty()){return;}
        for (Map.Entry<String,Object> entry: ioc.entrySet()) {
            //拿到实例对象中的所有属性
            Field[] fields = entry.getValue().getClass().getDeclaredFields();

            for (Field field : fields) {
                if (!field.isAnnotationPresent(GPAutowired.class)){continue;}
                GPAutowired autowired = field.getAnnotation(GPAutowired.class);
                String beanName = autowired.value().trim();
                if ("".equals(beanName)){
                    beanName = field.getType().getName();
                }
                //设置私有属性的访问权限
                field.setAccessible(true);
                //将指定对象变量上此 Field 对象表示的字段设置为指定的新值
                try {
                    field.set(entry.getValue(),ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                    continue;
                }
            }
        }
    }

    private void doInstance() {
        if (classNames.size() == 0){return;}

        try {
            for (String className : classNames) {
                Class<?> clazz = Class.forName(className);
                if (clazz.isAnnotationPresent(GPController.class)){
                    //默认将首字母小写作为beanName
                    String beanName = lowerFirst(clazz.getSimpleName());
                    ioc.put(beanName,clazz.newInstance());
                }else  if (clazz.isAnnotationPresent(GPService.class)){
                    GPService service = clazz.getAnnotation(GPService.class);
                    String beanName = service.value();
                    //如果用户设置了名字，就用用户设置的
                    if (!"".equals(beanName.trim())){
                        ioc.put(beanName,clazz.newInstance());
                        continue;
                    }

                    //如果没设，就按接口类型创建一个实例
                    Class<?>[] interfaces = clazz.getInterfaces();
                    for (Class<?> i: interfaces) {
                        ioc.put(i.getName(),clazz.newInstance());
                    }

                }else {
                    continue;
                }
            }
        }catch (Exception o){
            o.printStackTrace();
        }

    }

    /**
     * 首字母小写
     * 对字符做数字运算，相当于对ASCII码做运算
     * @param str
     *
     */
    private String lowerFirst(String str) {
        char[] chars = str.toCharArray();
        chars[0]+=32;
        return String.valueOf(chars);
    }

    //扫描出所有class文件
    private void doScanner(String packageName) {
        //将包路径转换成文件路径
        URL url = this.getClass().getClassLoader().getResource("/" + packageName.replaceAll("\\.", "/"));
        File dir = new File(url.getFile());
        for (File file : dir.listFiles()) {
            //如果是文件夹，继续递归
            if (file.isDirectory()){
                doScanner(packageName+"."+file.getName());
            }else{
                classNames.add(packageName +"."+file.getName().replace(".class","").trim());
            }
        }

    }

    private void doLoadConfig(String location) {
        InputStream fis = this.getClass().getClassLoader().getResourceAsStream(location);
        //1、读取配置文件
        try {
            p.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            try {
                if (null != fis) { fis.close();}
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }


    /**
     * Handler记录Controller中的RequestMapping和Method的对应关系
     */
    private class Handler{
        Object controller;//保存方法对应的实例
        Method method;//映射的方法
        Pattern pattern;
        Map<String,Integer> paramIndexMapping; //参数顺序

        public Handler(Pattern pattern,Object controller, Method method) {
            this.controller = controller;
            this.method = method;
            this.pattern = pattern;

            paramIndexMapping = new HashMap<String, Integer>();
            putParamIndexMapping(method);

        }

        private void putParamIndexMapping(Method method) {

            //提取方法中加入了注解的参数
            Annotation[][] pa = method.getParameterAnnotations();
            for (int i = 0; i < pa.length; i++) {
                for (Annotation a: pa[i]) {
                    if (a instanceof GPRequestParam){
                        String paramName = ((GPRequestParam) a).value();
                        if (!"".equals(paramName.trim())){
                            paramIndexMapping.put(paramName,i);
                        }
                    }
                }
            }

            //提取方法中的request和response参数
            Class<?>[] parameterTypes = method.getParameterTypes();

            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> type = parameterTypes[i];
                if (type ==  HttpServletRequest.class ||
                        type == HttpServletResponse.class){
                    paramIndexMapping.put(type.getName(),i);
                }
            }


        }
    }

}























