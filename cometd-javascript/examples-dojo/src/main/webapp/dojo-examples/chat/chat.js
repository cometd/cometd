dojo.require("dojox.cometd");
dojo.require("dojox.cometd.timestamp");
dojo.require("dojox.cometd.ack");


var room = {
    _last: "",
    _username: null,
    _connected: true,
    
    join: function(name){
    
        if (name == null || name.length == 0) {
            alert('Please enter a username!');
            return;
        }
        
        dojox.cometd.ackEnabled = (dojo.query("#ackInit").attr("checked") == "true");
        
        var cometURL = (new String(document.location).replace(/http:\/\/[^\/]*/, '').replace(/\/dojo-examples\/.*$/, '')) + "/cometd";
        dojox.cometd.init({ url: cometURL, logLevel: "info" });
        // For x-domain test change line above to:
        // dojox.cometd.init("http://127.0.0.1:8080/cometd/cometd");
        this._connected = true;
        
        this._username = name;
        dojo.addClass("join", "hidden");
        dojo.removeClass("joined", "hidden");
        dojo.byId("phrase").focus();
    },
    
    leave: function(){
        if (!room._username) {
            return;
        }
        
        if (room._meta) {
            dojo.unsubscribe(room._meta);
        }
        room._meta = null;
        
        dojox.cometd.startBatch();
        dojox.cometd.unsubscribe(room._subscription);
        dojox.cometd.publish("/chat/demo", {
            user: room._username,
            leave: true,
            chat: room._username + " has left"
        });
        dojox.cometd.endBatch();
        
        // switch the input form
        dojo.removeClass("join", "hidden");
        dojo.addClass("joined", "hidden");
        
        dojo.byId("username").focus();
        room._username = null;
        dojox.cometd.disconnect();
        dojo.byId('members').innerHTML = "";
    },
    
    chat: function(text){
        if (!text || !text.length) {
            return false;
        }
        var colons = text.indexOf("::");
        if (colons > 0) {
            dojox.cometd.publish("/service/privatechat", {
                room: "/chat/demo", // This should be replaced by the room name
                user: room._username,
                chat: text.substring(colons + 2),
                peer: text.substring(0, colons)
            });
        }
        else {
            dojox.cometd.publish("/chat/demo", {
                user: room._username,
                chat: text
            });
        }
    },
    
    _chat: function(message){
        if (!message.data) {
            // console.debug("bad message format " + message);
            return;
        }
        
        if (message.data instanceof Array) 
	{
            var members = dojo.byId('members');
            var list = "";
            for (var i in message.data) 
                list += message.data[i] + "<br/>";
            members.innerHTML = list;
        }
        else 
	{
            var chat = dojo.byId('chat');
            var from = message.data.user;
            var membership = message.data.join || message.data.leave;
            var text = message.data.chat;
            if (!text) 
                return;
            
            if (!membership && from == room._last) {
                from = "...";
            }
            else {
                room._last = from;
                from += ":";
            }
            
            if (membership) {
                chat.innerHTML += "<span class=\"membership\"><span class=\"from\">" + from + "&nbsp;</span><span class=\"text\">" + text + "</span></span><br/>";
                room._last = "";
            }
            else 
                if (message.data.scope == "private") {
                    chat.innerHTML += "<span class=\"private\"><span class=\"from\">" + from + "&nbsp;</span><span class=\"text\">[private]&nbsp;" + text + "</span></span><br/>";
                }
                else {
                    chat.innerHTML += "<span class=\"from\">" + from + "&nbsp;</span><span class=\"text\">" + text + "</span><br/>";
                }
            chat.scrollTop = chat.scrollHeight - chat.clientHeight;
        }
    },
    
    _init: function(){
        dojo.removeClass("join", "hidden");
        dojo.addClass("joined", "hidden");
        
        dojo.byId('username').focus();
        
        dojo.query("#username").attr({
            "autocomplete": "OFF"
        }).onkeyup(function(e){
            if (e.keyCode == dojo.keys.ENTER) {
                room.join(dojo.byId('username').value);
                
                return false;
            }
            return true;
        });
        
        dojo.query("#joinB").onclick(function(e){
            room.join(dojo.byId('username').value);
            e.preventDefault();
        });
        
        dojo.query("#phrase").attr({
            "autocomplete": "OFF"
        }).onkeyup(function(e){
            if (e.keyCode == dojo.keys.ENTER) {
                room.chat(dojo.byId('phrase').value);
                dojo.byId('phrase').value = '';
                e.preventDefault();
            }
        });
        
        dojo.query("#sendB").onclick(function(e){
            room.chat(dojo.byId('phrase').value);
            dojo.byId('phrase').value = '';
        });
        
        dojo.query("#leaveB").onclick(room, "leave");
    },
    
    _meta: function(e){
        if (e.action == "handshake") {	
            if (e.successful) {
                room._subscription=dojox.cometd.subscribe("/chat/demo", room, "_chat");
                dojox.cometd.publish("/chat/demo", {
                    user: room._username,
                    join: true,
                    chat: room._username + (e.reestablish?" has re-joined":" has joined")
                });
            }
		      
            if (e.reestablish) {
                room._chat({
                    data: {
                        join: true,
                        user: "SERVER",
                        chat: "handshake " + e.successful ? "Handshake OK" : "Failed"
                    }
                });
            }
        }
        else if (e.action == "connect") {
            if (e.successful && !this._connected) {
                room._chat({
                    data: {
                        join: true,
                        user: "SERVER",
                        chat: "reconnected!"
                    }
                });
            }
            if (!e.successful && this._connected) {
                room._chat({
                    data: {
                        leave: true,
                        user: "SERVER",
                        chat: "disconnected!"
                    }
                });
            }
            this._connected = e.successful;
        }
    }    
};
        
dojo.subscribe("/cometd/meta",room, room._meta);
dojo.addOnLoad(room, "_init");
dojo.addOnUnload(dojox.cometd, "disconnect");
