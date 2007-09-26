dojo.require("dojox.cometd");
dojo.require("dojo.behavior");

var room = {
	_last: "",
	_username: null,

	join: function(name){
		if(name == null || name.length==0 ){
			alert('Please enter a username!');
		}else{
			this._username=name;
			dojo.byId('join').className='hidden';
			dojo.byId('joined').className='';
			dojo.byId('phrase').focus();
			dojo.behavior.apply();

			// Really need to batch to avoid ordering issues
			dojox.cometd.subscribe("/chat/demo", false, room, "_chat");
			dojox.cometd.publish("/chat/demo", { user: room._username, join: true, chat : room._username+" has joined"});

			// XXX ajax.sendMessage('join', room._username);
		}
	},

	leave: function(){
		dojox.cometd.unsubscribe("/chat/demo", false, room, "_chat");
		dojox.cometd.publish("/chat/demo", { user: room._username, leave: true, chat : room._username+" has left"});

		// switch the input form
		dojo.byId('join').className='';
		dojo.byId('joined').className='hidden';
		dojo.byId('username').focus();
		dojo.behavior.apply();
		room._username=null;
	},
	  
	chat: function(text){
		if(!text || !text.length){ return false; }
		// lame attempt to prevent markup
		text=text.replace(/</g,'&lt;');
		text=text.replace(/>/g,'&gt;');

		dojox.cometd.publish("/chat/demo", { user: room._username, chat: text});
	},

	_chat: function(message){
		var chat=dojo.byId('chat');
		if(!message.data){
			alert("bad message format "+message);
			return;
		}
		var from=message.data.user;
		var special=message.data.join || message.data.leave;
		var text=message.data.chat;
		if(!text){ return; }

		if( !special && from == room._last ){
			from="...";
		}else{
			room._last=from;
			from+=":";
		}

		if(special){
			chat.innerHTML += "<span class=\"alert\"><span class=\"from\">"+from+"&nbsp;</span><span class=\"text\">"+text+"</span></span><br/>";
			room._last="";
		}else{
			chat.innerHTML += "<span class=\"from\">"+from+"&nbsp;</span><span class=\"text\">"+text+"</span><br/>";
		} 
		chat.scrollTop = chat.scrollHeight - chat.clientHeight;    
	},
  
  _init: function(){
		// XXX ajax.addListener('chat',room._chat);
		// XXX ajax.addListener('joined',room._joined);
		// XXX ajax.addListener('left',room._left);
		// XXX ajax.addListener('members',room._members);
		dojo.byId('join').className='';
		dojo.byId('joined').className='hidden';
		dojo.byId('username').focus();
		dojo.behavior.apply();
	}
};

dojo.addOnLoad(room, "_init");

var chatBehaviours = { 
	"#username" : function(element){
		element.setAttribute("autocomplete","OFF"); 
		dojo.connect(element, "onkeyup", function(e){   
			if(e.keyChar == dojo.keys.ENTER){
				room.join(dojo.byId('username').value);
				return false;
			}
			return true;
		});
	},
  
	'#joinB': function(element){
		element.onclick = function(){
			room.join(dojo.byId('username').value);
			return false;
		}
	},
  
	'#phrase' : function(element){
		element.setAttribute("autocomplete","OFF");
		dojo.connect(element, "onkeyup", function(e){   
			if(e.keyChar == dojo.keys.ENTER){
				room.chat(dojo.byId('phrase').value);
				dojo.byId('phrase').value='';
				return false;
			}
			return true;
		});
	},
  
	'#sendB': { "onclick": function(){
		room.chat(dojo.byId('phrase').value);
		dojo.byId('phrase').value='';
	} },
  
	'#leaveB': { "onclick": function(){
		room.leave();
	} }
};

dojo.behavior.add(chatBehaviours); 
// dojo.addOnUnload(dojox.cometd.disconnect);

