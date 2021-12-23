package we.plugin;

import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import we.Fizz;
import we.FizzAppContext;
import we.config.SystemConfig;
import we.filter.PreprocessFilter;
import we.plugin.auth.ApiConfig;
import we.plugin.auth.ApiConfigService;
import we.plugin.auth.ApiConfigServiceProperties;
import we.plugin.auth.GatewayGroupService;
import we.plugin.business.RouteExpertPluginFilter;
import we.plugin.stat.StatPluginFilter;
import we.plugin.stat.StatPluginFilterProperties;
import we.util.RangeUtils;
import we.util.ReflectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;


/**
 * @Description
 * @Author cuizhihui
 * @Date 2021/10/18 下午2:05
 */
@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class RouteTest {

    private MockServerHttpRequest request;

    private Map<String, Object> config = new HashMap<>();

    MockServerWebExchange exchange;

    @Before
    public void init() {
        request = MockServerHttpRequest.get("http://127.0.0.1:8600/proxy/xservice/ypath?orderCode=101004304985&version=1.0")
                .header("version","1.0")
                .header("ms-enterpriseMebId","2000000189")
                //.header("Content-Type","application/json")
                .header("X-FORWARDED-FOR","127.0.0.1")
                .cookie(new HttpCookie("version","1.0"))
                .build();
        exchange = MockServerWebExchange.from(request);

        config.put("pr1_status",true);
        config.put("pr2_status",true);
        config.put("pr3_status",true);
        config.put("pr1_routeType",1);
        config.put("pr2_routeType",2);
        config.put("pr3_routeType",2);

        config.put("pr1_ip","127.0.0.1");
        config.put("pr2_ip","127.0.0.1");
        config.put("pr3_ip","127.0.0.1");
        config.put("pr1_cookie","version=2.0|1.0");
        config.put("pr2_cookie","version=2.0|1.0");
        config.put("pr3_cookie","version=2.0|1.0");
        config.put("pr1_header","version=2.0|1.0");
        config.put("pr2_header","version=2.0|1.0");
        config.put("pr3_header","version=2.0|1.0");
        config.put("pr1_path","/ypath");
        config.put("pr2_path","/ypath");
        config.put("pr3_path","/ypath");
        config.put("pr1_targetParam","targetService=ms-corp-directly-connect,targetPath=/corp/queryEnterprise,targetMethod=GET,appId=2aee8f95-9a57-4008-9ff8-52b01dd0b0e9");
        config.put("pr2_targetParam","targetService=ms-corp-directly-connect,targetPath=/booking/queryOrder,targetMethod=POST");
        config.put("pr3_targetParam","targetService=ms-corp-directly-connect,targetPath=/hotel/getHotelIds,targetMethod=POST");

    }

    @Test
    public void testFilter(){
        RouteExpertPluginFilter routeExpertPluginFilter = new RouteExpertPluginFilter();
        StatPluginFilter statPluginFilter = new StatPluginFilter();
        StatPluginFilterProperties statPluginFilterProperties = new StatPluginFilterProperties();
        statPluginFilterProperties.setStatOpen(false);
        ReflectionUtils.set(routeExpertPluginFilter,"statPluginFilterProperties",statPluginFilterProperties);
        PreprocessFilter preprocessFilter = new PreprocessFilter();
        ApiConfigService apiConfigService = new ApiConfigService();
        SystemConfig systemConfig = new SystemConfig();
        systemConfig.setAggregateTestAuth(false);
        ReflectionUtils.set(apiConfigService,"systemConfig", systemConfig);

        ApiConfigServiceProperties apiConfigServiceProperties = new ApiConfigServiceProperties();
        apiConfigServiceProperties.setNeedAuth(false) ;
        ReflectionUtils.set(apiConfigService, "apiConfigServiceProperties", apiConfigServiceProperties);

        GatewayGroupService gatewayGroupService = new GatewayGroupService();
        ReflectionUtils.set(apiConfigService, "gatewayGroupService", gatewayGroupService);

        ReflectionUtils.set(preprocessFilter, "statPluginFilter", statPluginFilter);
        ReflectionUtils.set(routeExpertPluginFilter, "apiConfigService", apiConfigService);
        ReflectionUtils.set(preprocessFilter,"routeExpertPluginFilter", routeExpertPluginFilter);
        ReflectionUtils.set(preprocessFilter ,"gatewayGroupService",gatewayGroupService);

        Fizz.context = mock(ConfigurableApplicationContext.class);
        FizzAppContext.appContext = Fizz.context;

        ApiConfig ac = new ApiConfig(); // 一个路由配置
        ac.id = 1000; // 路由 id，建议从 1000 开始
        ac.service = "xservice"; // 前端服务名
        ac.path = "/ypath"; // 前端路径
        ac.type = ApiConfig.Type.REVERSE_PROXY; // 路由类型，此处为反向代理
        ac.httpHostPorts = Collections.singletonList("http://127.0.0.1:9094"); // 被代理接口的地址
        ac.backendPath = "/ypath"; // 被代理接口的路径
        ac.pluginConfigs = new ArrayList<>();

        PluginConfig pc2 = new PluginConfig();
        pc2.plugin = RouteExpertPluginFilter.ROUTE_EXPERT; // 应用 id 为 demoPlugin 的插件
        ac.pluginConfigs.add(pc2);
    }

    @Test
    public void testPlugin() {
        RouteExpertPluginFilter routeExpertPluginFilter = new RouteExpertPluginFilter();
        routeExpertPluginFilter.doFilter(exchange,config);
    }

    @Test
    public void testIp() {
        System.out.println(RangeUtils.ipHit("127.0.0.1","127.0.0.1-15"));
    }

    @Test
    public void testRangeInDefined() {
        System.out.println(RangeUtils.rangeInDefined(5,1,10));
    }

    @Test
    public void testRange() {
        System.out.println(RangeUtils.hitRule("9","1-10"));
        System.out.println(RangeUtils.hitRule("11","1-10"));
        System.out.println(RangeUtils.hitRule("10","10|11"));
        System.out.println(RangeUtils.hitRule("12","10|11"));
        System.out.println(RangeUtils.hitRule("12","*|11"));
        System.out.println(RangeUtils.hitRule("12","10|*"));
    }

    @Test
    public void testUrl() throws Exception{
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.set("11","11");
        params.set("22","22");
        params.set("33","33");
        String joinUrl = "http://localhost:8600/proxy/protected-ms-core-corp-member/enterprise/inBlacklist";
        String b = UriComponentsBuilder.fromHttpUrl(joinUrl).replaceQueryParams(params).build().toString();
        System.out.println(b);
    }

    @Test
    public void testParam() {
        MultiValueMap<String, String> paramMap = exchange.getRequest().getQueryParams();
        System.out.println(RangeUtils.paramHit(paramMap,"version=1.0|2.0"));
        System.out.println(RangeUtils.paramHit(paramMap,"version=2.0"));
        System.out.println(RangeUtils.paramHit(paramMap,"code=5,version=1.0|2.0"));
    }

    @Test
    public void testCookie() {
        MultiValueMap<String, HttpCookie> cookies = exchange.getRequest().getCookies();
        System.out.println(RangeUtils.cookiesHit(cookies,"version=1.0|2.0"));
        System.out.println(RangeUtils.cookiesHit(cookies,"version=2.0"));
        System.out.println(RangeUtils.cookiesHit(cookies,"code=5,version=1.0|2.0"));
    }

    @Test
    public void testHeader() {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        System.out.println(RangeUtils.headerHit(headers,"version=1.0|2.0"));
        System.out.println(RangeUtils.headerHit(headers,"version=2.0"));
        System.out.println(RangeUtils.headerHit(headers,"code=5,version=1.0|2.0"));
    }

}

