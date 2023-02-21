package com.fizzgate.plugin.business;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import com.alibaba.fastjson.JSONObject;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import reactor.core.publisher.Mono;
import com.fizzgate.plugin.FizzPluginFilterChain;
import com.fizzgate.plugin.auth.ApiConfig;
import com.fizzgate.plugin.auth.ApiConfigService;
import com.fizzgate.plugin.requestbody.RequestBodyPlugin;
import com.fizzgate.proxy.Route;
import com.fizzgate.util.DataUtils;
import com.fizzgate.util.NettyDataBufferUtils;
import com.fizzgate.util.RangeUtils;
import com.fizzgate.util.RestTemplateUtils;
import com.fizzgate.util.WebUtils;
import com.fizzgate.vo.RouteRule;
import com.fizzgate.vo.RouteTypeResponse;

/**
 * 路由能手插件
 * @author cuizhihui, qiuwenjie
 */
@Component(RouteExpertPluginFilter.ROUTE_EXPERT)
public class RouteExpertPluginFilter extends  RequestBodyPlugin {

    private static final Logger log = LoggerFactory.getLogger(RouteExpertPluginFilter.class);

    @Resource
    private ApiConfigService apiConfigService;

    @Resource
    private RestTemplateUtils restTemplateUtils;

    public static final String ROUTE_EXPERT = "RouteExpert";
    
    public static final String TARGET_SERVICE = "targetService";

    public static final String TARGET_PATH = "targetPath";

    public static final String TARGET_METHOD = "targetMethod";

    public static final String APPID = "appId";

    public static final String LOCALHOST = "http://localhost";

    public static final String FIZZ_APPID = "fizz-appid";

    /**
     * 插件入口函数
     */
    @Override
    public Mono<Void> doFilter(ServerWebExchange exchange, Map<String, Object> config) {
        ServerHttpRequest clientReq = exchange.getRequest();
        String rid = clientReq.getId();
        //请求体
        HttpHeaders headers = clientReq.getHeaders();
        //cookies
        MultiValueMap<String, HttpCookie> cookies = clientReq.getCookies();
        //当前路由对象
        Route route = WebUtils.getRoute(exchange);
        //调用方ip
        String ip = WebUtils.getOriginIp(exchange);

        return clientReq.getBody().defaultIfEmpty(NettyDataBufferUtils.EMPTY_DATA_BUFFER)
                .single()
                .flatMap(body -> {
                    return doShareOrCopy(exchange,rid,headers,cookies,route,ip,body,config);
                }
          );
    }

    /**
     * 执行主体分派复制逻辑
     * @param exchange
     * @param rid
     * @param headers 请求头
     * @param cookies cookies
     * @param route 原路由
     * @param ip 请求Ip
     * @param body 请求体
     * @param config 前端路由配置
     * @return
     */
    private Mono<Void> doShareOrCopy(ServerWebExchange exchange,String rid,HttpHeaders headers,
    		MultiValueMap<String, HttpCookie> cookies,Route route,String ip,DataBuffer body,Map<String, Object> config){
        Map paramMap = null;
        ServerHttpRequest clientReq = exchange.getRequest();
        if(HttpMethod.GET.equals(clientReq.getMethod())){
            //get取url的参数
            MultiValueMap<String, String> params = clientReq.getQueryParams();
            paramMap = params;
        }else{
            //post取body的参数
            String bodyJson = DataUtils.decode(body);
            paramMap = DataUtils.getByJson(bodyJson);
        }

        RouteTypeResponse routeTypeResponse = dataConvert(config,route);
        //先处理所有复制规则，采用异步线程方式（执行结果不影响当前主线程）
        List<RouteRule> copyRules = routeTypeResponse.getCopyRules();
        if(!CollectionUtils.isEmpty(copyRules)){
            //多线程复制任务派发
            ExecutorService executorService = new ThreadPoolExecutor(3, 3,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<Runnable>(), new ThreadFactoryBuilder().setNameFormat("copyWorks").build());
            for (RouteRule cpRule: copyRules) {
                //校验规则是否命中
                boolean pass = checkHitRouteRule(cpRule,headers,cookies,paramMap,ip,route.backendPath);
                if(pass){
                    executorService.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                HttpHeaders copyHeaders = new HttpHeaders();
                                copyHeaders.addAll(headers);
                                if(StringUtils.isNotBlank(cpRule.getAppId())){
                                    copyHeaders.set(FIZZ_APPID,cpRule.getAppId());
                                }
                                String joinUrl = routeUrlJoin(LOCALHOST,clientReq.getLocalAddress().getPort(),cpRule.getTargetService(),cpRule.getTargetPath());
                                joinUrl = UriComponentsBuilder.fromHttpUrl(joinUrl).replaceQueryParams(clientReq.getQueryParams()).build().toString();
                                String resp = sendUrl(joinUrl,StringUtils.isNotBlank(cpRule.getTargetMethod())?
                                				HttpMethod.valueOf(cpRule.getTargetMethod()): route.method,copyHeaders,DataUtils.decode(body));
                                log.info("[RouteExpert] copy request Path:{},resp:{}",cpRule.getTargetPath(),resp);
                            }catch (Exception e){
                                log.error("[RouteExpert] 复制请求异常 cpRule:{}", cpRule != null?JSONObject.toJSONString(cpRule):null);
                            }
                        }
                    });
                }else{
                    log.warn("[RouteExpert] 复制规则不符合 规则如下:{}",cpRule != null?JSONObject.toJSONString(cpRule):null);
                }
            }
        }
        //处理分派路由方式规则
        List<RouteRule> shareRules = routeTypeResponse.getShareRules();
        boolean stopShare = false;
        if(!CollectionUtils.isEmpty(shareRules)){
            for (RouteRule shareRule: shareRules) {
                if(stopShare){
                    //分派只能命中一次
                    break;
                }
                if(shareRule == null){
                    continue;
                }
                //目标路由是否已设置
                boolean isTargetRouteConfig = !StringUtils.isBlank(shareRule.getTargetParam());
                //校验规则是否命中
                boolean pass = checkHitRouteRule(shareRule,headers,cookies,paramMap,ip,route.backendPath);
                if(pass){
                    stopShare= true;
                    //检查目标路由相关值是否已设置，若未正常设置表示目标路由无效，返回失败
                    if(isTargetRouteConfig && StringUtils.isBlank(shareRule.getTargetService()) && StringUtils.isBlank(shareRule.getTargetPath()) ){
                        return WebUtils.responseError(exchange, HttpStatus.UNAUTHORIZED.value(), "[RouteExpert] 查找不到目标路由");
                    }
                    if(!isTargetRouteConfig){
                        //按原路由响应
                        log.info("[RouteExpert] share request service:{},path:{}",route.backendService,route.backendPath);
                        return FizzPluginFilterChain.next(exchange);
                    }else{
                        try {
                            String bUrl = shareRule.getTargetPath();
                            //转发请求到目标路由
                            return share(exchange,shareRule,bUrl,headers.getFirst(FIZZ_APPID));
                        }catch (Exception e){
                            log.error("[RouteExpert] 转发请求异常 shareRule:{}", shareRule != null?JSONObject.toJSONString(shareRule):null);
                        }
                    }
                }
                //不匹配任何条件且目标路由未设置（即使用当前路由），表示不通过请求过滤，返回失败
                if(!isTargetRouteConfig && !pass ){
                    return WebUtils.responseError(exchange, HttpStatus.UNAUTHORIZED.value(), "[RouteExpert] 不满足路由规则匹配条件，请求处理中止");
                }
            }
        }
        log.info("[RouteExpert] share request service:{},path:{}",route.backendService,route.backendPath);
        //(没分派规则)或者(规则不通过并且目标路由不为空)，按原路由响应
        return FizzPluginFilterChain.next(exchange);
    }

    /**
     * 拼接路由路径
     * @param ip
     * @param port 端口
     * @param targetService 目标服务
     * @param targetPath 目标路径
     * @return
     */
    private String routeUrlJoin(String ip,int port,String targetService,String targetPath){
        StringBuffer targetUrlSb = new StringBuffer().append(LOCALHOST).append(":").append(port).append("/proxy/");
        if(StringUtils.isNotBlank(targetService)){
            targetUrlSb.append(targetService);
        }
        if(StringUtils.isNotBlank(targetPath)){
            targetUrlSb.append("/").append(targetPath);
        }
        return targetUrlSb.toString();
    }

    /**
     * 转发路由
     * @param exchange
     * @param shareRule 转发路由对象(含新appId)
     * @param bUrl 转发url
     * @param origAppId 原appId
     * @return
     */
    private Mono<Void> share(ServerWebExchange exchange,RouteRule shareRule,String bUrl,String origAppId){
        Route route = WebUtils.getRoute(exchange);
        if(StringUtils.isNotBlank(shareRule.getAppId())){
            WebUtils.appendHeader(exchange,FIZZ_APPID,shareRule.getAppId());
        }
        //路由转发
        if(StringUtils.isNotBlank(shareRule.getTargetService())){
            route.backendService(shareRule.getTargetService());
        }

        if(StringUtils.isNotBlank(shareRule.getTargetMethod())){
            route.method(HttpMethod.valueOf(shareRule.getTargetMethod()));
        }

        if(StringUtils.isNotBlank(bUrl)){
            route.backendPath(bUrl);
        }
        //目前版本仅支持服务发现
        route.type(2);
        ApiConfig br = apiConfigService.getApiConfig(StringUtils.isNotBlank(shareRule.getAppId())?
        												shareRule.getAppId():origAppId,route.backendService,route.method,route.backendPath);
        boolean isNewRouteExist = (br != null) && (StringUtils.isNotBlank(shareRule.getTargetService()) && StringUtils.isNotBlank(shareRule.getTargetPath()));
        if(isNewRouteExist){
            //转发到新路由加载新插件
            route.pluginConfigs(br.pluginConfigs);
        }else if(br == null){
            //查找不到目标路由
            return WebUtils.responseError(exchange, HttpStatus.UNAUTHORIZED.value(), "[RouteExpert] 查找不到目标路由");
        }
        log.info("[RouteExpert] share request service:{},path:{}",route.backendService,route.backendPath);
        return FizzPluginFilterChain.next(exchange);
    }

    /**
     * 校验是否命中路由
     * @param routeRule 待命中规则
     * @param headers 请求头
     * @param cookies
     * @param paramMap 请求参数
     * @param ip 来源ip
     * @param path 来源地址
     * @return
     */
    private boolean checkHitRouteRule(RouteRule routeRule,HttpHeaders headers,
    		MultiValueMap<String, HttpCookie> cookies,Map paramMap,String ip,String path){
        if(routeRule == null){
            return false;
        }

        if(RangeUtils.urlHit(path,routeRule.getPath()) 
        		&& RangeUtils.ipHit(ip,routeRule.getIp()) 
        		&& RangeUtils.cookiesHit(cookies,routeRule.getCookie()) 
        		&& RangeUtils.headerHit(headers,routeRule.getHeader()) 
        		&& RangeUtils.paramHit(paramMap,routeRule.getParam())){
            return true;
        }

        return false;
    }

    /**
     * 将配置的路由规则信息转换为路由数据对象
     * @param config 前端模板参数
     * @param route 路由
     * @return
     */
    private RouteTypeResponse dataConvert(Map<String, Object> config,Route route){
        RouteTypeResponse routeTypeResponse = new RouteTypeResponse();
        try {
            Field[] allFields =RouteRule.class.getDeclaredFields();
            //默认初始3个规则，前端约束prX_
            String[] frontArray = {"pr1_","pr2_","pr3_"};
            List<RouteRule> shareRules = new ArrayList<>();
            routeTypeResponse.setShareRules(shareRules);
            List<RouteRule> copyRules = new ArrayList<>();
            routeTypeResponse.setCopyRules(copyRules);

            RouteRule routeRule = null;
            for (String front: frontArray) {
                routeRule = new RouteRule();
                StringBuffer keyBuffer = null;
                for (Field field : allFields) {
                    keyBuffer = new StringBuffer();
                    keyBuffer.append(front).append(field.getName());
                    Object valueObj = config.get(keyBuffer.toString());
                    if(valueObj == null){
                        continue;
                    }

                    try {
                        PropertyDescriptor pd = new PropertyDescriptor(field.getName(), RouteRule.class);
                        Method setMethod = pd.getWriteMethod();
                        //代理该字段set方法并赋值
                        setMethod.invoke(routeRule,valueObj);
                    }catch (Exception e){
                        log.error("[RouteExpert] 转换{}字段异常",field.getName());
                        continue;
                    }
                }

                //处理目标路由
                if(StringUtils.isNotBlank(routeRule.getTargetParam())){
                    String[] targetParamArray = StringUtils.split(routeRule.getTargetParam(), ',');
                    if(targetParamArray != null && targetParamArray.length > 0){
                        String key = null,value = null;
                        for (String keyValue: targetParamArray) {
                            String[] keyValueArray = StringUtils.split(keyValue, '=');
                            key = keyValueArray[0];
                            value = keyValueArray[1];
                            if(StringUtils.isNotBlank(value) && TARGET_SERVICE.equals(key)){
                                routeRule.setTargetService(value);
                            }else if(StringUtils.isNotBlank(value) && TARGET_METHOD.equals(key)){
                                routeRule.setTargetMethod(value);
                            }else if(StringUtils.isNotBlank(value) && TARGET_PATH.equals(key)){
                                routeRule.setTargetPath(value);
                            }else if(APPID.equals(key)){
                                routeRule.setAppId(value);
                            }
                        }
                    }
                }else if(routeRule.getRouteType() != null && routeRule.getRouteType() == 2 
                			&& StringUtils.isBlank(routeRule.getTargetParam())){
                    log.error("[RouteExpert] 转换路由：{}，参数异常",JSONObject.toJSONString(routeRule));
                }

                //转换param
                if(routeRule.getRouteType() != null && routeRule.getRouteType() == 1 && routeRule.getStatus()
                        && checkPathRepeat(routeRule.getTargetService(),routeRule.getTargetPath(),route)){
                    //分派，分派只执行第一个
                    shareRules.add(routeRule);
                }else if(routeRule.getRouteType() != null && routeRule.getRouteType() == 2 
                		&& routeRule.getStatus() && StringUtils.isNotBlank(routeRule.getTargetPath())
                        && StringUtils.isNotBlank(routeRule.getTargetMethod())
                        && StringUtils.isNotBlank(routeRule.getTargetService())
                        && checkPathRepeat(routeRule.getTargetService(),routeRule.getTargetPath(),route)){
                    //复制
                    copyRules.add(routeRule);
                }else if(routeRule.getRouteType() != null && routeRule.getStatus()){
                    log.error("[RouteExpert] 转换路由：{}，参数异常",JSONObject.toJSONString(routeRule));
                }

            }
        }catch (Exception e){
            log.error("[RouteExpert] 转换RouteTypeResponse路由失败：{}",e.getMessage());
        }

        return routeTypeResponse;
    }

    /**
     * 发送请求 url
     * @param url
     * @param method
     * @param headers
     * @param param
     * @return
     */
    private String sendUrl(String url, HttpMethod method, HttpHeaders headers,String param) {
        HttpEntity<String> requestEntity = new HttpEntity(param,headers);
        ResponseEntity<String> resp = restTemplateUtils.exchange(url,method,requestEntity,String.class,null,null);
        return resp.getBody();
    }

    /**
     * 判断复制路径是否自身一样，防止造成死循环
     * @param targetService
     * @param targetPath
     * @param route
     * @return
     */
    private boolean checkPathRepeat(String targetService,String targetPath,Route route){
        if(StringUtils.isNotBlank(targetService) && StringUtils.isNotBlank(targetPath) && targetService.equals(route.backendService) && targetPath.equals(route.backendPath)){
           //目标路由跟原路由一致会导致循环
            log.error("[RouteExpert] 目标路由不能与原路由一致,targetService:{},targetPath:{}",targetService,targetPath);
            return false;
        }
        return true;
    }
}
