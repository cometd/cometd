dojo.provide("dojopong.Paddle");

dojopong.Paddle = function(surface, startX, /** 0 == left, 1 == right **/ side, parent)
{
	// Constants
	this.COLOR = new Array(51, 80, 142, 1);
	this.PADDLE_WIDTH = 15;
	this.PADDLE_HEIGHT = 120;
	
	this._left = 0;
	this._parent = parent;
	this._rect = null;
	this._side = side;
	this._startX = startX;
	this._surface = surface;
	this._top = 0;
	this._velocity = 10;
}

dojo.extend(dojopong.Paddle, {

	getPositionInfo: function()
	{
		return {
			left: this._left,
			top: this._top,
			velocity: this._velocity
		};
	},
	
	init: function()
	{
		this._createPaddle(this._startX, this._side);
	},
	
	onKeyPress: function(e)
	{
		// Right paddle
		if (this.isUserControlled)
		{
			if (e.key == "w")
			{
				this.moveUp();
			}
			if (e.key == "s")
			{
				this.moveDown();
			}
			var p = new Object();
			//var label = (!this._side) ? "paddleLeft":"paddleRight";
			if (!this._side)
			{
				p.paddleLeft = this.getPositionInfo();
			}
			else {
				p.paddleRight = this.getPositionInfo();
			}
			cometd.publish("/dojopong/position", { positionInfo: p, source: cometd.clientId });
		}
	},
	
	moveDown: function()
	{
		this._move(this._velocity);
	},
	
	moveUp: function()
	{
		this._move(-1*this._velocity);
	},
	
	setPositionInfo: function(positionInfo)
	{
		var dy = positionInfo.top - this._top;
		
		this._left = positionInfo.left;
		this._top = positionInfo.top;
		this._velocity = positionInfo.velocity;
		
		this._rect.applyTransform({
			dy: dy
		});
	},
	
	_createPaddle: function(x, side)
	{
		this._top = 10;
		this._left = x;
		
		this._rect = this._surface.createRect({
			x: this._left,
			y: this._top,
			width: this.PADDLE_WIDTH,
			height: this.PADDLE_HEIGHT,
			r: 0
		});
		
		this._rect.setFill(this.COLOR);
	},
	
	_move: function(velocity)
	{
		this._top += velocity;
		this._rect.applyTransform({
			dy: velocity
		});
		if (this._parent.isMaster)
		{
			this._parent.sendPositionInfo();
		}
	}
});