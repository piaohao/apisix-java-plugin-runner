/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.apisix.plugin.runner.filter;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import lombok.extern.slf4j.Slf4j;
import org.apache.apisix.plugin.runner.HttpRequest;
import org.apache.apisix.plugin.runner.HttpResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Slf4j
@Component
public class GrayFilter implements PluginFilter {

    @Override
    public String name() {
        /* It is recommended to keep the name of the filter the same as the class name.
         Configure the filter to be executed on apisix's routes in the following format

        {
            "uri": "/hello",
            "plugins": {
                "ext-plugin-pre-req": {
                    "conf": [{
                        "name": "RewriteRequestDemoFilter",
                        "value": "bar"
                    }]
                }
            },
            "upstream": {
                "nodes": {
                    "127.0.0.1:1980": 1
                },
                "type": "roundrobin"
            }
        }

        The value of name in the configuration corresponds to the value of return here.
         */

        return "GrayFilter";
    }

    @Override
    public void filter(HttpRequest request, HttpResponse response, PluginFilterChain chain) {
        /*
         * If the conf you configured is of type json, you can convert it to Map or json.
         */

        String configStr = request.getConfig(this);
//        Gson gson = new Gson();
//        Map<String, Object> conf = new HashMap<>();
//        conf = gson.fromJson(configStr, conf.getClass());
        JSONObject conf = JSON.parseObject(configStr);

//        /*
//         * You can use the parameters in the configuration.
//         */
//
//        // note: the path to the rewrite must start with '/'
//        request.setPath((String) conf.get("rewrite_path"));
//        request.setHeader((String) conf.get("conf_header_name"), (String) conf.get("conf_header_value"));
//        /* note: The value of the parameter is currently a string type.
//                 If you need the json type, you need the upstream service to parse the string value to json.
//                 For example, if the arg is set as below
//                 request.setArg("new arg", "{\"key1\":\"value1\",\"key2\":2}");
//
//                 The arg received by the upstream service will be as below
//                 "new arg": "{\"key1\":\"value1\",\"key2\":2}"
//         */
//        request.setArg((String) conf.get("conf_arg_name"), (String) conf.get("conf_arg_value"));
//
//        /*
//         * You can fetch the Nginx variables, and the request body
//         */
//        String remoteAddr = request.getVars("remote_addr");
//        String serverPort = request.getVars("server_port");
//        String body = request.getBody();

        String upstreamId = conf.getString("upstream_id");
        log.info("upstream_id:{}", upstreamId);

        //get grayName
        String grayName = request.getHeader("gray-name");
        log.info("headers信息={}", JSON.toJSONString(request.getHeaders()));
        if (StrUtil.isBlank(grayName)) {
            chain.filter(request, response);
            return;
        }

        String body = HttpUtil.createGet("http://192.168.29.101:30254/apisix/admin/upstreams/" + upstreamId)
                .header("x-api-key", "edd1c9f034335f136f87ad84b625c8f1")
                .execute()
                .body();
        log.info("上游信息={}", body);
        JSONObject json = JSON.parseObject(body);
        JSONObject value = json.getJSONObject("value");
        if (value.containsKey("service_name")) {
            try {
                String serviceName = value.getString("service_name");

                String serverAddr = "192.168.29.5:8848";
                String namespace = "test";
                String group = "DEFAULT_GROUP";
                Properties properties = new Properties();
                properties.put(PropertyKeyConst.SERVER_ADDR, serverAddr);
                properties.put(PropertyKeyConst.NAMESPACE, namespace);
                NamingService namingService = NacosFactory.createNamingService(properties);
                List<Instance> allInstances = namingService.getAllInstances(serviceName, group);
                log.info("nacos服务={}", JSON.toJSONString(allInstances));
                boolean hasGrayService = allInstances.stream()
                        .anyMatch(ins -> ins.isHealthy() && ins.containsMetadata("gray-name") && ins.getMetadata().get("gray-name").equals(grayName));
                if (hasGrayService) {
                    log.info("设置请求头");
                    request.setHeader("force-gray", "true");
                    request.setVars(Map.of("force-gray", "true"));
                }
            } catch (NacosException e) {
                log.error(e.getMessage(), e);
            }
        } else if (value.containsKey("nodes")) {
            try {
                JSONObject node = value.getJSONArray("nodes").getJSONObject(0);
                String url = node.getString("host");
                String port = node.getString("port");
                int status = HttpUtil.createGet(url + ":" + port)
                        .timeout(2000)
                        .execute()
                        .getStatus();
                boolean hasGrayService = true;
                if (status != 200) {
                    hasGrayService = false;
                }
                if (hasGrayService) {
                    log.info("设置请求头");
                    request.setHeader("force-gray", "true");
                    request.setVars(Map.of("force-gray", "true"));
                }
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
//        log.info("headers:{}", JSON.toJSONString(request.getHeaders()));
        chain.filter(request, response);
    }

    /**
     * If you need to fetch some Nginx variables in the current plugin, you will need to declare them in this function.
     *
     * @return a list of Nginx variables that need to be called in this plugin
     */
    @Override
    public List<String> requiredVars() {
        List<String> vars = new ArrayList<>();
        vars.add("remote_addr");
        vars.add("server_port");
        return vars;
    }

    /**
     * If you need to fetch request body in the current plugin, you will need to return true in this function.
     */
    @Override
    public Boolean requiredBody() {
        return true;
    }
}
