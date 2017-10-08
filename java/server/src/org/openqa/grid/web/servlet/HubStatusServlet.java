// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.grid.web.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openqa.grid.common.exception.GridException;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSlot;
import org.openqa.selenium.remote.CapabilityType;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * API to query the hub config remotely.
 *
 * use the API by sending a GET to grid/api/hub/
 * with the content of the request in JSON,specifying the
 * parameters you're interesting in, for instance, to get
 * the timeout of the hub and the registered servlets :
 *
 * {"configuration":
 *      [
 *      "timeout",
 *      "servlets"
 *      ]
 * }
 *
 * alternatively you can use a query string ?configuration=timeout,servlets
 *
 * if no param is specified, all params known to the hub are returned.
 *
 */
public class HubStatusServlet extends RegistryBasedServlet {

    public HubStatusServlet() {
        super(null);
    }

    public HubStatusServlet(final Registry registry) {
        super(registry);
    }

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        process(request, response);
    }

    protected void process(final HttpServletRequest request, final HttpServletResponse response)
            throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(200);
        JsonObject res;
        try {
            res = getResponse(request);
            response.getWriter().print(res);
            response.getWriter().close();
        } catch (JsonSyntaxException e) {
            throw new GridException(e.getMessage());
        }

    }

    private JsonObject getResponse(final HttpServletRequest request) throws IOException {
        JsonObject res = new JsonObject();
        res.addProperty("success", true);
        try {
            if (request.getInputStream() != null) {
                JsonObject requestJSON = getRequestJSON(request);
                List<String> keysToReturn = null;

                if (request.getParameter("configuration") != null && !"".equals(request.getParameter("configuration"))) {
                    keysToReturn = Arrays.asList(request.getParameter("configuration").split(","));
                } else if (requestJSON != null && requestJSON.has("configuration")) {
                    keysToReturn = new Gson().fromJson(requestJSON.getAsJsonArray("configuration"), ArrayList.class);
                }

                Registry registry = getRegistry();
                JsonElement config = registry.getConfiguration().toJson();
                for (Map.Entry<String, JsonElement> entry : config.getAsJsonObject().entrySet()) {
                    if (keysToReturn == null || keysToReturn.isEmpty() || keysToReturn.contains(entry.getKey())) {
                        res.add(entry.getKey(), entry.getValue());
                    }
                }
                if (keysToReturn == null || keysToReturn.isEmpty() || keysToReturn.contains("newSessionRequestCount")) {
                    res.addProperty("newSessionRequestCount", registry.getNewSessionRequestCount());
                }

                if (keysToReturn == null || keysToReturn.isEmpty() || keysToReturn.contains("nodes")) {
                    res.add("nodes", getNodes());
                }
                if (keysToReturn == null || keysToReturn.isEmpty() || keysToReturn.contains("browsers")) {
                    res.add("browsers", getBrowsers());
                }
            }
        } catch (Exception e) {
            res.remove("success");
            res.addProperty("success", false);
            res.addProperty("msg", e.getMessage());
        }
        return res;

    }

    /**
     * Get nodes list.
     * @return
     * @throws JsonSyntaxException
     */
    private JsonArray getNodes() throws JsonSyntaxException {
        JsonArray nodes = new JsonArray();
        for (RemoteProxy proxy : getRegistry().getAllProxies()) {
            JsonObject node = new JsonObject();
            String remoteHost = proxy.getOriginalRegistrationRequest().getConfiguration().getRemoteHost();
            node.addProperty("host", remoteHost);
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * Get browsers availability.
     * @return
     * @throws JsonSyntaxException
     */
    private JsonArray getBrowsers() throws JsonSyntaxException {

        // Récupération des navigateurs avec dispo sur chaque sonde
        Map<String, Integer> freeBrowserSlots = new HashMap<>();
        Map<String, Integer> totalBrowserSlots = new HashMap<>();
        for (RemoteProxy proxy : getRegistry().getAllProxies()) {
            for (TestSlot slot : proxy.getTestSlots()) {
                String slot_browser_name = slot.getCapabilities().get(CapabilityType.BROWSER_NAME).toString().toUpperCase();
                if (slot.getSession() == null) {
                    if (freeBrowserSlots.containsKey(slot_browser_name)) {
                        freeBrowserSlots.put(slot_browser_name, freeBrowserSlots.get(slot_browser_name) + 1);
                    } else {
                        freeBrowserSlots.put(slot_browser_name, 1);
                    }
                }
                if (totalBrowserSlots.containsKey(slot_browser_name)) {
                    totalBrowserSlots.put(slot_browser_name, totalBrowserSlots.get(slot_browser_name) + 1);
                } else {
                    totalBrowserSlots.put(slot_browser_name, 1);
                }
            }
        }

        // Construction de l'objet Json
        JsonArray browsers = new JsonArray();
        for (Entry<String, Integer> entry : totalBrowserSlots.entrySet()) {
            JsonObject browser = new JsonObject();
            browser.addProperty("name", entry.getKey());
            browser.addProperty("total", entry.getValue());
            if (freeBrowserSlots.containsKey(entry.getKey())) {
                browser.addProperty("free", freeBrowserSlots.get(entry.getKey()));
            } else {
                browser.addProperty("free", 0);
            }
            browsers.add(browser);
        }
        return browsers;
    }

    private JsonObject getRequestJSON(final HttpServletRequest request) throws IOException {
        JsonObject requestJSON = null;
        BufferedReader rd = new BufferedReader(new InputStreamReader(request.getInputStream()));
        StringBuilder s = new StringBuilder();
        String line;
        while ((line = rd.readLine()) != null) {
            s.append(line);
        }
        rd.close();
        String json = s.toString();
        if (!"".equals(json)) {
            requestJSON = new JsonParser().parse(json).getAsJsonObject();
        }
        return requestJSON;
    }
}
