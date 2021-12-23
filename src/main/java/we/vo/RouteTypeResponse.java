package we.vo;

import lombok.Data;

import java.util.List;

/**
 * @author cuizhihui
 */
@Data
public class RouteTypeResponse {
    /**
     * 复制路由
     */
    private List<RouteRule> copyRules;

    /**
     * 分派路由
     */
    private List<RouteRule> shareRules;

}
