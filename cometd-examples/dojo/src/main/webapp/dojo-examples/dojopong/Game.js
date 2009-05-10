dojo.provide("dojopong.Game");

dojo.require("dojopong.Ball");
dojo.require("dojopong.Paddle");
dojo.require("dojopong.Player");

// @TODO: Fix lag conditions.
dojopong.Game = function()
{
	this.ASYCN_POSITION_NOTIFIER_REPEAT = 30;
	
	// The game ball.
	this.ball = null;
	
	// @TODO: Finish this definition
	// A master is a client that sends the initial positions at the start of the game;
	// however, both masters and slaves can send positions at any time.  Any recipient
	// of a position must honor the sent position.  Lags may cause pauses in game play.
	this.isMaster = false;
	
	// When true, it means that both players have connected, and at least the current
	// user has sent and received a /dojopong/ready topic.
	this.isReady = false;
	
	// 
	this.isStarted = false;
	
	// The left paddle.
	this.paddleLeft = null;
	
	// The right paddle
	this.paddleRight = null;
	
	this.opponent = new dojopong.Player();
	this.player = new dojopong.Player();
	
	this._asyncPositionNotifierInterval = null;
	this._parent = null;
	this._runnerInterval = null;
	this._surface_size = null;
	
	this.opponent.init();
	this.player.init();
	
}

dojo.extend(dojopong.Game, {
		
		getPositionInfo: function()
		{
			var msg = {};
			
			if (this.ball)
			{
				msg.ball = this.ball.getPositionInfo();
			}
			if (this.player.playerId == 1)
			{
				if (this.paddleLeft)
				{
					msg.paddleLeft = this.paddleLeft.getPositionInfo();
				}
			}
			if (this.player.playerId == 2)
			{
				if (this.paddleRight)
				{
					msg.paddleRight = this.paddleRight.getPositionInfo();
				}
			}
			
			return msg;
		},
		
		init: function(parent)
		{
			this._parent = parent;
			dojo.debug("this.game: " + this);
			cometd.subscribe("/dojopong/join", false, this, "onJoin");
			cometd.subscribe("/dojopong/position", false, this, "onPosition");
			cometd.subscribe("/dojopong/ready", false, this, "onReady");
			cometd.subscribe("/dojopong/start", false, this, "onStart");
			
			var surface = this._createGameSurface();
			
			this.ball = new dojopong.Ball(surface);
			this.ball.init();
			
			// @TODO: move this somewhere
			// this._runnerInterval = setInterval(dojo.lang.hitch(this, "onAnimate"), 25);
		},
		
		onAnimate: function()
		{
			var ballRight = this.ball._left+(this.ball._radius*2);
			var ballBottom = this.ball._top+(this.ball._radius*2);
			var paddleLeftBottom = this.paddleLeft._top+120;
			var paddleLeftRight = this.paddleLeft._left+15;
			var paddleRightBottom = this.paddleRight._top+120;
			var isBounced = false;
			
			if (ballRight > this._surface_size.width || this.ball._left < 0)
			{
				this.ball.bounceX();
				isBounced = true;
			}
			else if (ballBottom > this._surface_size.height || this.ball._top < 0)
			{
				this.ball.bounceY();
				isBounced = true;
			}
			// Bounce off right paddle?
			else if (ballRight > this.paddleRight._left
				&& (
					ballBottom > this.paddleRight._top && this.ball._top < paddleRightBottom
					|| this.ball._top < paddleRightBottom && ballBottom > this.paddleRight._top
				)
			)
			{
				this.ball.bounceX();
				isBounced = true;
			}
			// Bounce off left paddle?
			else if (this.ball._left < paddleLeftRight
				&& (
					ballBottom > this.paddleLeft._top && this.ball._top < paddleLeftBottom
					|| this.ball._top < paddleLeftBottom && ballBottom > this.paddleLeft._top
				)
			)
			{
				this.ball.bounceX();
				isBounced = true;
			}
			
			this.ball.move();
			if (isBounced && this.isMaster)
			{
				//*this.stopBall();
				this.sendPositionInfo(this.getPositionInfo());
			}
			
		},
		
		onJoin: function(message)
		{
            dojo.debug("onJoin");
			if (message.data.source == cometd.clientId)
			{
				this.player.isConnected = true;
			}
			else
			{
				dojo.debug("onJoin: VISIBLE!");
				this.opponent.isConnected = true;
				this._parent.readyButtonNode.style.visibility = "visible";
			}
		},
		
		onPosition: function(message)
		{
			// Ball position should always come through... the master controls ball movement
			if (message && message.data && message.data.positionInfo)
			{
				if (!this.isMaster && message.data.positionInfo.ball)
				{
					this.stopBall();
					this.ball.setPositionInfo(message.data.positionInfo.ball);
					this.startBall();
				}
				// @TODO: Document why this is this.isMaster and not !this.isMaster
				if (message.data.positionInfo.paddleLeft && this.isMaster)
				{
					this.paddleLeft.setPositionInfo(message.data.positionInfo.paddleLeft);
					//dojo.debug("paddleLeft received");
				}
				// @TODO: Document why this is !this.isMaster and not this.isMaster
				if (message.data.positionInfo.paddleRight && !this.isMaster)
				{
					this.paddleRight.setPositionInfo(message.data.positionInfo.paddleRight);
					//dojo.debug("paddleRight received");
				}
			}
		},
		
		onStart: function(message)
		{
			dojo.debug("onStart:" + cometd.clientId + "," + message.data.sourceId);
			if (!this.isStarted)
			{
				this.isStarted = true;
				dojo.debug()
				this.isMaster = (cometd.clientId == message.data.sourceId);
				if (!this.isMaster)
				{
					dojo.debug("is not master");
					if (message.data.positionInfo)
					{
						this.setPositionInfo(message.data.positionInfo);
					}
				}
				else
				{
					dojo.debug("is master");
				}
				this.startBall();
				
				if (this.isMaster)
				{
					this.startAsyncPositionNotifier();
				}
			}
		},
		
		onReady: function(message)
		{
			dojo.debug("ONREADY: " + message.data.source);
			// Only process if the game is not in a ready state
			//if (!this.isReady)
			//{
				// If we are getting this message then the player is connected
				this.player.isConnected = true;
				
				// Player is ready, but may be waiting on opponent to be ready
				if (message.data.source == cometd.clientId)
				{
					this.player.isReady = true;
					this._parent.readyButtonNode.style.visibility = "hidden";
				}
				else
				{
					// Opponent connected before player
					if (!this.opponent.isConnected)
					{
						this.opponent.isConnected = true;
					}
					if (this.player.isConnected && !this.player.isReady)
					{
						dojo.debug("this.player.isConnected && !this.player.isReady: VISIBLE!");
						this._parent.readyButtonNode.style.visibility = "visible";
					}
					this.opponent.isReady = true;
				}
				
				if (this.player.isReady && !this.opponent.isReady)
				{
					dojo.debug("you are player 1");
					this.player.setPlayer(1, this.paddleLeft, true);
					this.opponent.setPlayer(2, this.paddleRight, false);
				}
				else if (!this.player.isReady && this.opponent.isReady)
				{
					dojo.debug("you are player 2");
					this.player.setPlayer(2, this.paddleRight, true);
					this.opponent.setPlayer(1, this.paddleLeft, false);
				}
				
				// Both players are connected and ready
				if (this.player.isConnected && this.player.isReady
					&& this.opponent.isConnected && this.opponent.isReady)
				{
					dojo.debug("paddle 2");
					this.isReady = true;
					dojo.event.topic.publish("/dojopong/game/ready", {});
					dojo.debug("game is ready");
				}
			//}
		},
		
		sendPositionInfo: function(positionInfo)
		{
			cometd.publish("/dojopong/position", { positionInfo: this.getPositionInfo(), source: cometd.clientId });
		},
		
		setPositionInfo: function(positionInfo)
		{
			if (positionInfo)
			{
				if (positionInfo.ball)
				{
					this.ball.setPositionInfo(positionInfo.ball);
				}
				if (positionInfo.paddleLeft)
				{
					this.paddleLeft.setPositionInfo(positionInfo.paddleLeft);
				}
				if (positionInfo.paddleRight)
				{
					this.paddleRight.setPositionInfo(positionInfo.paddleRight);
				}
			}
			else
			{
				dojo.debug("no position info");
			}
		},
		
		start: function()
		{
			if (!this.isStarted)
			{
				cometd.publish("/dojopong/start", { positionInfo: this.getPositionInfo(), sourceId: cometd.clientId });
			}
		},
		
		startAsyncPositionNotifier: function()
		{
			this._asyncPositionNotifierInterval = setInterval(dojo.lang.hitch(this, "sendPositionInfo"), this.ASYCN_POSITION_NOTIFIER_REPEAT);
		},
		
		startBall: function()
		{
			// @FIXME: The ball movement is not smooth if the slave application is trying to animate.
			// A potential fix will be to test the best possible frame rate for the slave which will work
			// best with the masters frame rate.
			if (this.isMaster)
			{
				this._runnerInterval = setInterval(dojo.lang.hitch(this, "onAnimate"), 25);
			}
		},
		
		stopAsyncPositionNotifier: function()
		{
			clearInterval();
		},
		
		stopBall: function()
		{
			clearInterval(this._runnerInterval);
		},
		
		_createGameSurface: function()
		{
			var container = dojo.byId("game");
			var container_position = dojo.html.abs(container);
			var surface = dojo.gfx.createSurface(container, 500, 500);
			this._surface_size = surface.getDimensions();
			
			this._surface_size.width  = parseInt(this._surface_size.width);
			this._surface_size.height = parseInt(this._surface_size.height);
			
			this.paddleLeft = new dojopong.Paddle(surface, 20, 0, this);
			this.paddleLeft.init();
			dojo.event.connect(document, "onkeypress", this.paddleLeft, "onKeyPress");
			
			this.paddleRight = new dojopong.Paddle(surface, this._surface_size.width - (15+20), 1, this);
			this.paddleRight.init();
			dojo.event.connect(document, "onkeypress", this.paddleRight, "onKeyPress");
			
			return surface;
		}
});
