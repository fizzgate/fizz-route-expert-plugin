package we.vo;

import lombok.Data;

/**
 * @author cuizhihui
 */
@Data
public class RouteRule {

    /**
     * 是否起效 true 起效 false 失效
     */
    private Boolean status;

    /**
     * 路由方式 1.分派 2.复制
     */
    private Integer routeType;

    /**
     * 匹配来源路径 ps：当前路由的子路径，可以是到某一级路径或到某个具体接口，不填或/**为当前路由所有路径
     */
    private String path;

    /**
     * 填写单个IP、多个IP或IP段，逗号隔开。来源IP匹配上则条件触发
     */
    private String ip;

    /**
     * cookie 填写一个或多个cookie字段名及其值（固定值，允许多个值，用竖线隔开），每个cookie参数为并的关系（逗号隔开），例如填 "version=2.0|3.0，os=andriod", 表示 cookie里name为version的字段为2.0或3.0直接且os为andriod时匹配条件。  为空相当于不使用此条件
     */
    private String cookie;

    /**
     * 请求头 填写一个或多个header字段名及其值（固定值，允许多个值，用竖线隔开），每个header参数为并的关系（逗号隔开），例如填 "version=2.0|3.0，os=andriod", 表示 header里name为version的字段为2.0或3.0直接且os为andriod时匹配条件。  为空相当于不使用此条件
     */
    private String header;

    /**
     * 请求参数
     */
    private String param;

    /**
     * 目标服务名
     */
    private String targetService;

    /**
     * 目标路径
     */
    private String targetPath;

    /**
     * 目标请求方式
     */
    private String targetMethod;

    /**
     * 目标appId
     */
    private String appId;

    /**
     * 目标请求参数
     */
    private String targetParam;

}
