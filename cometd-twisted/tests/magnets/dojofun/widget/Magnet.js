dojo.provide("dojofun.widget.Magnet");
dojo.require("dojo.dnd.HtmlDragMove");
dojo.require("dojo.dnd.HtmlDragMoveSource");
dojo.require("dojo.dnd.HtmlDragMoveObject");

dojo.widget.defineWidget("dojofun.widget.Magnet", dojo.widget.HtmlWidget, { 
	templatePath: dojo.uri.dojoUri("../FunWithDojo/dojofun/widget/templates/Magnet.html"),
	templateCssPath: dojo.uri.dojoUri("../FunWithDojo/dojofun/widget/templates/Magnet.css"),

	initializer: function() {
		this.phrase = "hello world";
		this.domNodeClass="magnet";
		this.constrainTo="";
	},

	postCreate: function() {
		this.domNode.innerHTML=this.phrase;
		dojo.html.addClass(this.domNode, this.domNodeClass);
		dojo.html.disableSelection(this.domNode);

		this.drag = new dojo.dnd.HtmlDragMoveSource(this.domNode);
		this.drag.constrainTo(dojo.byId(this.constrainTo));
	}
});
