dojo.require("dojox.cometd");
dojo.require("dojox.cometd.timestamp");

var room = {
	_last: "",
	_username: null,
	_connected: true,
	
	join: function(name){
	
		if (name == null || name.length == 0) {
			alert('Please enter a username!');
			return;
		}
		
		var loc = (new String(document.location).replace(/http:\/\/[^\/]*/, '').replace(/\/examples\/.*$/, '')) +
		"/cometd";
		dojox.cometd.init(loc);
		// For x-domain test change line above to:
		// dojox.cometd.init("http://127.0.0.1:8080/cometd/cometd");
		this._connected = true;
		
		this._username = name;
		dojo.addClass("join", "hidden");
		dojo.removeClass("joined", "hidden");
		dojo.byId("phrase").focus();
		
		// subscribe and join
		dojox.cometd.startBatch();
		dojox.cometd.subscribe("/chat/demo", room, "_chat");
		dojox.cometd.publish("/chat/demo", {
			user: room._username,
			join: true,
			chat: room._username + " has joined"
		});
		dojox.cometd.endBatch();
		
		// handle cometd failures while in the room
		if (room._meta) {
			dojo.unsubscribe(room._meta, null, null);
		}
		room._meta = dojo.subscribe("/cometd/meta", this, function(e){
			// console.debug(e);
			if (e.action == "handshake") {
				if (e.reestablish) {
					if (e.successful) {
						dojox.cometd.subscribe("/chat/demo", room, "_chat");
						dojox.cometd.publish("/chat/demo", {
							user: room._username,
							join: true,
							chat: room._username + " has re-joined"
						});
					}
					room._chat({
						data: {
							join: true,
							user: "SERVER",
							chat: "handshake " + e.successful ? "Handshake OK" : "Failed"
						}
					});
				}
			}
			else 
				if (e.action == "connect") {
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
		});
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
		dojox.cometd.unsubscribe("/chat/demo", room, "_chat");
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
	},
	
	chat: function(text){
		if (!text || !text.length) {
			return false;
		}
		dojox.cometd.publish("/chat/demo", {
			user: room._username,
			chat: text
		});
	},
	
	_chat: function(message){
		var chat = dojo.byId('chat');
		if (!message.data) {
			// console.debug("bad message format " + message);
			return;
		}
		var from = message.data.user;
		var special = message.data.join || message.data.leave;
		var text = message.data.chat;
		if (!text) {
			return;
		}
		
		if (!special && from == room._last) {
			from = "...";
		}
		else {
			room._last = from;
			from += ":";
		}
		
		if (special) {
			chat.innerHTML += "<span class=\"alert\"><span class=\"from\">" + from + "&nbsp;</span><span class=\"text\">" + text + "</span></span><br/>";
			room._last = "";
		}
		else {
			chat.innerHTML += "<span class=\"from\">" + from + "&nbsp;</span><span class=\"text\">" + text + "</span><br/>";
		}
		chat.scrollTop = chat.scrollHeight - chat.clientHeight;
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
	}
};

dojo.addOnLoad(room, "_init");
dojo.addOnUnload(room, "leave");

//vim:ts=4:noet:
