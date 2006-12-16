dojo.provide("dojopong.Ball");
dojo.require("dojo.gfx.*");

dojopong.Ball = function(surface)
{
	this.COLOR = new Array(51, 80, 142);
	this.RADIUS = 10;
	this._circle = null;
	this._left = 0;
	this._surface = surface;
	this._top = 0;
	this._velocityX = 0;
	this._velocityY = 0;
	this._x = 0;
	this._y = 0;
}

dojo.extend(dojopong.Ball, {
	
	init: function()
	{
		this._createCircle();
	},
	
	bounceX: function()
	{
		this._velocityX = -this._velocityX;
	}, 
	
	bounceY: function()
	{
		this._velocityY = -this._velocityY;
	},
	
	getNextX: function()
	{
		this._left += this._velocityX;
		this._x += this._velocityX;
		
		return this._velocityX;
	},
	
	getNextY: function()
	{
		this._top += this._velocityY;
		this._y += this._velocityY;
		
		return this._velocityY;
	},
	
	getPositionInfo: function()
	{
		return {
			left: this._left,
			radius: this.RADIUS,
			top: this._top,
			velocityX: this._velocityX,
			velocityY: this._velocityY,
			x: this._x,
			y: this._y
		};
	},
	
	move: function(deltas)
	{
		var dx = (deltas) ? deltas.dx : this.getNextX();
		var dy = (deltas) ? deltas.dy : this.getNextY();
		this._circle.applyTransform({
			dx: dx,
			dy: dy
		});
	},
	
	setPositionInfo: function(positionInfo)
	{
		var dx = positionInfo.x - this._x;
		var dy = positionInfo.y - this._y;
		
		this._left = positionInfo.left;
		this._top = positionInfo.top;
		this._velocityX = positionInfo.velocityX;
		this._velocityY = positionInfo.velocityY;
		this._x = positionInfo.x;
		this._y = positionInfo.y;
		
		this.move({dx: dx, dy: dy});
	},
	
	_createCircle: function()
	{
		this._top = 250;
		this._left = 250;
		this._radius = this.RADIUS;
		this._x = this._left + this._radius;
		this._y = this._top + this._radius;
		
		// @FIXME: I think cx is the cartesian coordinate center
		this._circle = this._surface.createCircle({
			cx: this._x,
			cy: this._y,
			r: this._radius
		});
		this._circle
		
		this._circle.setFill(this.COLOR);
		this._velocityX = randRange(-10, 10);
		this._velocityY = randRange(-10, 10);
	}
	
});