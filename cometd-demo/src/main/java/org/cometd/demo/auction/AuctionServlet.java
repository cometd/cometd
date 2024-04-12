/*
 * Copyright (c) 2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.demo.auction;

import jakarta.servlet.http.HttpServlet;
import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.oort.Oort;
import org.cometd.oort.Seti;

public class AuctionServlet extends HttpServlet {
    private ServerAnnotationProcessor _processor;
    private AuctionService _auction;
    private AuctionChatService _chat;

    @Override
    public void init() {

        Oort oort = (Oort)getServletContext().getAttribute(Oort.OORT_ATTRIBUTE);
        Seti seti = (Seti)getServletContext().getAttribute(Seti.SETI_ATTRIBUTE);
        _processor = (ServerAnnotationProcessor)getServletContext().getAttribute(ServerAnnotationProcessor.class.getName());
        _auction = new AuctionService();
        _processor.process(_auction, oort, seti);
        _chat = new AuctionChatService();
        _processor.process(_chat, oort, seti);
    }

    @Override
    public void destroy() {
        _processor.deprocess(_chat);
        _processor.deprocess(_auction);
    }
}
