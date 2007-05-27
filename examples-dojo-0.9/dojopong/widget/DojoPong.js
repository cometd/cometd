/*
	Copyright (c) 2004-2006, The Dojo Foundation
	All Rights Reserved.

	Licensed under the Academic Free License version 2.1 or above OR the
	modified BSD license. For more information on Dojo licensing, see:

		http://dojotoolkit.org/community/licensing.shtml
*/

dojo.provide("dojopong.widget.DojoPong");
dojo.require("dojo.widget.*");
dojo.require("dojo.widget.HtmlWidget");
dojo.require("dojo.event.*");
dojo.require("dojopong.Game");
dojo.require("dojo.io.cometd");
dojo.require("dojopong.widget.CountDown");


dojo.widget.defineWidget(
	"dojopong.widget.DojoPong",
	dojo.widget.HtmlWidget,
	function(){
		// Must instantiate cometd here because including it within the widget yield unexpected results.
		cometd.init({ }, "http://localhost:8080/cometd");
		this.countDown = null;
		this.countDownNode = null;
		this.game = new dojopong.Game();
		this.gameNode = null;
		this.readyButtonNode = null;
		this.readyButtonNode = null;
	},
	{
		templatePath: dojo.uri.dojoUri("../dojopong/widget/templates/DojoPong.html"),
		//templateCssPath: dojo.uri.dojoUri("../dojopong/widget/templates/CountDown.css"),
		
		beginGame: function(data)
		{
			dojo.debug("beginGame!!!!!");
			this.countDown.start();
		},
		
		postCreate: function()
		{
			dojo.debug("postCreate: " + this.countDownNode);
			
			this.countDown = dojo.widget.createWidget(
				"CountDown",
				{
					countDownCaption: "Game is starting in...",
					finishDelaySeconds: 1,
					finishCaption: "GO!",
					seconds: 2
				},
				this.countDownNode
			);
			
			this.game.init(this);
			this.setUpConnection();
			//dojo.debug("this.game.player: " + this.game.player.setReady);
			dojo.event.connect(this.readyButtonNode, "onclick", this.game.player, "setReady");
		},
		
		init: function()
		{
			dojo.debug("init");
		},
		
		serverFrame: function(msg)
		{
			dojo.debug("serverFrame from: " + msg.data.source);
			//dojo.debug("serverFrame:" + msg);
			//cometd.subscribed("/dojopong/frame", msg);
		},

		setUpConnection: function()
		{
			dojo.debug("setUpConnection");
			setTimeout(dojo.lang.hitch(this, "setUpEvents"), 3000);
		},

		setUpEvents: function()
		{
            dojo.debug("setUpEvents");
			cometd.subscribe("/dojopong/frame", false, "serverFrame");
			
			var fakePlayerNames = new Array("bob", "alex", "dylan", "bill", "jeanine", "ann", "dave", "steve", "elb", "andre", "jeff");
			
            dojo.debug("cometd: " + cometd);
			cometd.publish("/dojopong/join", { nickname: fakePlayerNames[randRange(0, fakePlayerNames.length-1)], source: cometd.clientId });
			dojo.event.topic.subscribe("/dojopong/game/ready", this, "beginGame");
			dojo.event.topic.subscribe("/dojopong/countdown/finish", this, "startGame");
		},
		
		// @TODO: Finish this method... it gets called at the end of the countdown
		startGame: function()
		{
            dojo.debug("startGame");
			this.game.start();
		},

		testConnection: function()
		{
			cometd.publish("/dojopong/frame", { msg: "frame!", source: cometd.clientId });
		},
		
		testPositioning: function()
		{
			this.game.ball.setPositionInfo({
				left: 15,
				radius: 10,
				top: 15,
				velocityX: 10,
				velocityY: 10,
				x: 25,
				y: 25
			});
		}
	}
);
