dojo.provide("dojopong.widget.CountDown");
dojo.require("dojo.widget.*");
dojo.require("dojo.widget.HtmlWidget");

dojo.widget.defineWidget(
	"dojopong.widget.CountDown",
	dojo.widget.HtmlWidget,
	function(){
		this.rootNode = null;
		this.countDownCaption = "Count down:",
		this.finishDelaySeconds = 1,
		this.finishCaption = "GO!",
		this.seconds = 5,
		this._ticks = 0,
		this._tickInterval = null
	},
	{
		templatePath: dojo.uri.dojoUri("../dojopong/widget/templates/CountDown.html"),
		templateCssPath: dojo.uri.dojoUri("../dojopong/widget/templates/CountDown.css"),
		
		decrement: function()
		{
			dojo.debug("decrement");
			// This is the last decrement in the interval loop
			if (this._ticks <= 0)
			{
				clearInterval(this._tickInterval);
				this.finish();
			}
			else
			{
				this.rootNode.innerHTML = this.countDownCaption + this._ticks.toString() + "";
				--this._ticks;
			}
			
		},
		
		finish: function()
		{
			this.rootNode.innerHTML = this.finishCaption;
			dojo.event.topic.publish("/dojopong/countdown/finish", {test:1});
		},
		
		postCreate: function()
		{
		},
		
		start: function()
		{
			this._ticks = this.seconds;
			this.decrement();
			this._tickInterval = setInterval(dojo.lang.hitch(this, "decrement"), 1000);
		}
	}
);
