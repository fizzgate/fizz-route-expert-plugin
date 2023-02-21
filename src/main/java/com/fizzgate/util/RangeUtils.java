package com.fizzgate.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.fizzgate.util.Constants;
import com.fizzgate.util.UrlTransformUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

/**
 * 范围工具类
 * @author cuizhihui
 */
public class RangeUtils {

    public static final String RANG_ONE = "*";
    public static final String RANG_TWO = "-";
    public static final int RANG_THREE = 2;
    public static final String RANG_FOUR = "|";
    public static final String RANG_FIVE = "/**";
    public static final int RANG_SIX = '-';

    /**
     * 获取ip白名单
     * @param ips
     * @return
     */
    private static Map<String, String[]> getIpsMap(String ips) {
        Map<String, String[]> ipMap = new HashMap<>(6);
        if (StringUtils.isNotBlank(ips)) {
            Arrays.stream(StringUtils.split(ips, ',')).forEach(
                    ip -> {
                        ip = ip.trim();
                        int i = ip.lastIndexOf('.');
                        String subnet = ip.substring(0, i).trim();
                        String addrSeg = ip.substring(i + 1).trim();
                        if (RANG_ONE.equals(addrSeg)) {
                            ipMap.put(subnet, new String[]{"2", "254"});
                        } else if (addrSeg.indexOf(RANG_SIX) > 0) {
                            String[] a = StringUtils.split(addrSeg, '-');
                            String beg = a[0].trim();
                            String end = a[1].trim();
                            ipMap.put(subnet, new String[]{beg, end});
                        } else {
                            ipMap.put(subnet, new String[]{addrSeg, addrSeg});
                        }
                    }
            );
        }
        return ipMap;
    }

    /**
     * 校验Ip是否符合规则
     * @param ip
     * @param ipRange
     * @return
     */
    public static boolean ipHit(String ip,String ipRange) {
        if(StringUtils.isBlank(ipRange)){
            return true;
        }else if(StringUtils.isBlank(ip)){
            return false;
        }
        
        Map<String, String[]> ips = getIpsMap(ipRange);
        int originSubnetLen = ip.lastIndexOf(Constants.Symbol.DOT);
        
        for (Map.Entry<String, String[]> e : ips.entrySet()) {
            String subnet = e.getKey();
            int subnetLen = subnet.length();
            byte i = 0;
            if (subnetLen == originSubnetLen) {
                for (; i < subnetLen; i++) {
                    if (subnet.charAt(i) != ip.charAt(i)) {
                        break;
                    }
                }
                if (i == subnetLen) {
                    int originAddrLen = ip.length() - originSubnetLen - 1;
                    String[] addrSeg = e.getValue();
                    String addrSegBeg = addrSeg[0];
                    String addrSegEnd = addrSeg[1];
                                        
                    if (originAddrLen < addrSegBeg.length() || addrSegEnd.length() < originAddrLen) {
                        return false;
                    } else {
                        if (originAddrLen == addrSegBeg.length()) {
                            for (byte j = 0; j < addrSegBeg.length(); j++) {
                                if (ip.charAt(originSubnetLen + 1 + j) < addrSegBeg.charAt(j)) {
                                    return false;
                                }
                            }
                        }
                        if (originAddrLen == addrSegEnd.length()) {
                            for (byte j = 0; j < addrSegEnd.length(); j++) {
                                if (addrSegEnd.charAt(j) < ip.charAt(originSubnetLen + 1 + j)) {
                                    return false;
                                }
                            }
                        }
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    /**
     * 通用规则命中，目前仅是符合请求参数，请求头，cookies
     * @param target
     * @param range
     * @return
     */
    public static boolean hitRule(String target,String range){
        if(StringUtils.isBlank(target) || StringUtils.isBlank(range) ){
            return false;
        }

        //只要含*，不为空都返回true
        if(RANG_ONE.equals(range) && StringUtils.isNotBlank(target)) {
            return true;
        }else if(range.contains(RANG_TWO) && NumberUtils.isDigits(target)){
            String[] rangArray = range.split("-");
            if(rangArray.length == RANG_THREE &&NumberUtils.isDigits(rangArray[0]) && NumberUtils.isDigits(rangArray[1])){
                return rangeInDefined(Integer.parseInt(target),Integer.parseInt(rangArray[0]),Integer.parseInt(rangArray[1]));
            }
        }else if(range.contains(RANG_FOUR)){
            String[] rangeArray = range.split("[|]");
            for (String r:rangeArray) {
                //值相等或者只要含*，不为空都返回true
            	boolean isValHit = target.equals(r) || ( RANG_ONE.equals(r) && StringUtils.isNotBlank(target) );
                if(isValHit){
                    return true;
                }
            }
        }else{
            return target.equals(range);
        }
        return false;
    }

    /**
     * cookies是否符合规则
     * @param cookies
     * @param cookiesRange
     * @return
     */
    public  static boolean cookiesHit(MultiValueMap<String, HttpCookie> cookies,String cookiesRange){
        if(StringUtils.isBlank(cookiesRange)){
            return true;
        }else if(cookies == null || cookies.isEmpty()){
            return false;
        }
        
        String[] cookiesRangeArray = StringUtils.split(cookiesRange, ',');
        if(cookiesRangeArray != null && cookiesRangeArray.length > 0){
            for (String cookiesKv: cookiesRangeArray) {
                cookiesKv = cookiesKv.trim();
                String[] cookiesRangArray = StringUtils.split(cookiesKv,'=');
                if(cookiesRangArray != null && cookiesRangArray.length ==2){
                    String cookiesRangName = cookiesRangArray[0];
                    String cookiesRangValue = cookiesRangArray[1];
                    if(cookies.containsKey(cookiesRangName) && cookies.getFirst(cookiesRangName) != null 
                    		&& hitRule(cookies.getFirst(cookiesRangName).getValue(),cookiesRangValue)){
                        return true;
                    }
                }
            }
        }else{
            return true;
        }
        return false;
    }

    /**
     * 请求头是否符合规则
     * @param headers
     * @param headerRange
     * @return
     */
    public static boolean headerHit(HttpHeaders headers, String headerRange){
        if(StringUtils.isBlank(headerRange)){
            return true;
        }else if(headers == null || headers.isEmpty()){
            return false;
        }
        
        String[] headerRangeArray = StringUtils.split(headerRange, ',');
        if(headerRangeArray != null && headerRangeArray.length > 0){
            for (String headerKv: headerRangeArray) {
                headerKv = headerKv.trim();
                String[] headerRangArray = StringUtils.split(headerKv,'=');
                if(headerRangArray != null && headerRangArray.length ==2){
                    String headersRangName = headerRangArray[0];
                    String headerRangValue = headerRangArray[1];
                    if(headers.containsKey(headersRangName) && headers.getFirst(headersRangName) != null 
                    		&& hitRule(headers.getFirst(headersRangName),headerRangValue)){
                        return true;
                    }
                }
            }
        }else{
            return true;
        }
        return false;
    }

    /**
     * 请求参数是否符合规则
     * @param paramMap
     * @param paramRange
     * @return
     */
    public  static boolean paramHit(Map paramMap, String paramRange){
        if(StringUtils.isBlank(paramRange)){
            return true;
        }else if(paramMap == null || paramMap.isEmpty()){
            return false;
        }
        
        String[] paramRangeArray = StringUtils.split(paramRange, ',');
        if(paramRangeArray != null && paramRangeArray.length > 0){
            for (String paramKv: paramRangeArray) {
                paramKv = paramKv.trim();
                String[] paramRangArray = StringUtils.split(paramKv,'=');
                if(paramRangArray != null && paramRangArray.length ==2){
                    String paramRangName = paramRangArray[0];
                    String paramRangValue = paramRangArray[1];
                    if(paramMap.containsKey(paramRangName) && paramMap.get(paramRangName) != null 
                    		&& hitRule(paramMap.get(paramRangName) != null?String.valueOf(paramMap.get(paramRangName)).replaceAll("\\[","").replaceAll("]",""):null,paramRangValue)){
                        return true;
                    }
                }
            }
        }else{
            return true;
        }
        return false;
    }

    /**
     * 请求路径是否符合规则
     * @param url
     * @param urlRange
     * @return
     */
    public static boolean urlHit(String url,String urlRange){
        return UrlTransformUtils.ANT_PATH_MATCHER.match(urlRange,url);
    }

    /**
     * 判断范围
     * @param current
     * @param min
     * @param max
     * @return
     */
    public static boolean rangeInDefined(int current, int min, int max) {
        return Math.max(min, current) == Math.min(current, max);
    }

}
