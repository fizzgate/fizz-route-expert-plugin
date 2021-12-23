package we.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.buffer.DataBuffer;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据处理工具类
 * @author cuizhihui
 */
public class DataUtils {

    /**
     * 将json提交的参数转换成map
     * 转换失败则返回空
     * @param jsonString
     * @return
     */
    public static Map<String, Object> getByJson(String jsonString) {
        Map<String, Object> map = new HashMap<>(0);
        if (StringUtils.isEmpty(jsonString)) {
            return map;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            map = mapper.readValue(jsonString, Map.class);
        } catch (IOException e) {

        }
        return map;
    }

    /**
     * urldecode
     * @param body
     * @return
     */
    public static String decode(DataBuffer body){
        try{
            return URLDecoder.decode(body.toString(StandardCharsets.UTF_8), "UTF-8");
        }catch (UnsupportedEncodingException e){
            return "";
        }
    }

}
