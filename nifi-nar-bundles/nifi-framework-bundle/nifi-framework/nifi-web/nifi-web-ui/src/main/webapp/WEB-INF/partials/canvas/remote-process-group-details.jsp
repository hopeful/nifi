<%--
 Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
--%>
<%@ page contentType="text/html" pageEncoding="UTF-8" session="false" %>
<div id="remote-process-group-details" nf-draggable="{containment: 'parent', handle: '.dialog-header'}">
    <div class="dialog-content">
        <div class="setting">
            <div class="setting-name">Name</div>
            <div class="setting-field">
                <span id="read-only-remote-process-group-name"></span>
            </div>
        </div>
        <div class="setting">
            <div class="setting-name">Id</div>
            <div class="setting-field">
                <span id="read-only-remote-process-group-id"></span>
            </div>
        </div>
        <div class="setting">
            <div class="setting-name">URL</div>
            <div class="setting-field">
                <span id="read-only-remote-process-group-url"></span>
            </div>
        </div>
        <div class="setting">
            <div class="remote-process-group-timeout-setting">
                <div class="setting-name">
                    Communications timeout
                    <img class="setting-icon icon-info" src="images/iconInfo.png" alt="Info" title="When communication with this remote process group takes longer than this amount of time, it will timeout."/>
                </div>
                <div class="setting-field">
                    <span id="read-only-remote-process-group-timeout"></span>
                </div>
            </div>
            <div class="remote-process-group-yield-duration-setting">
                <div class="setting-name">
                    Yield duration
                    <img class="setting-icon icon-info" src="images/iconInfo.png" alt="Info" title="When communication with this remote process group fails, it will not be scheduled again until this amount of time elapses."/>
                </div>
                <div class="setting-field">
                    <span id="read-only-remote-process-group-yield-duration"></span>
                </div>
            </div>
            <div class="clear"></div>
        </div>
        <div class="setting">
            <div class="setting-name">
                Transport Protocol
                <img class="setting-icon icon-info" src="images/iconInfo.png" alt="Info" title="Transport protocol to use for this Remote Process Group communication."/>
            </div>
            <div class="setting-field">
                <div id="read-only-remote-process-group-transport-protocol"></div>
            </div>
        </div>
        <div class="setting">
            <div class="remote-process-group-proxy-host-setting">
                <div class="setting-name">
                    HTTP Proxy server hostname
                    <img class="setting-icon icon-info" src="images/iconInfo.png" alt="Info" title="Specify the proxy server's hostname to use. If not specified, HTTP traffics are sent directly to the target NiFi instance."/>
                </div>
                <div class="setting-field">
                    <span id="read-only-remote-process-group-proxy-host"></span>
                </div>
            </div>
            <div class="remote-process-group-proxy-port-setting">
                <div class="setting-name">
                    HTTP Proxy server port
                    <img class="setting-icon icon-info" src="images/iconInfo.png" alt="Info" title="Specify the proxy server's port number, optional. If not specified, default port 80 will be used."/>
                </div>
                <div class="setting-field">
                    <span id="read-only-remote-process-group-proxy-port"></span>
                </div>
            </div>
            <div class="clear"></div>
        </div>
        <div class="setting">
            <div class="remote-process-group-proxy-user-setting">
                <div class="setting-name">
                    HTTP Proxy user
                    <img class="setting-icon icon-info" src="images/iconInfo.png" alt="Info" title="Specify an user name to connect to the proxy server, optional."/>
                </div>
                <div class="setting-field">
                    <span id="read-only-remote-process-group-proxy-user"></span>
                </div>
            </div>
            <div class="remote-process-group-proxy-password-setting">
                <div class="setting-name">
                    HTTP Proxy password
                    <img class="setting-icon icon-info" src="images/iconInfo.png" alt="Info" title="Specify an user password to connect to the proxy server, optional."/>
                </div>
                <div class="setting-field">
                    <span id="read-only-remote-process-group-proxy-password"></span>
                </div>
            </div>
            <div class="clear"></div>
        </div>
    </div>
</div>