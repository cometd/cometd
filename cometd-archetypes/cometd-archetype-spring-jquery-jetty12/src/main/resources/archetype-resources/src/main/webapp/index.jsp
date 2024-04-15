<!DOCTYPE html>
<html>
<head>
    <meta http-equiv="Content-Type" content="text/html;charset=UTF-8" />
    <script type="importmap">
        {
          "imports": {
            "jquery": "https://code.jquery.com/jquery-3.7.1.js",
            "cometd/": "js/cometd/",
            "jquery/cometd": "../../js/jquery/jquery.cometd.js"
          }
        }
    </script>
    <script type="module" src="application.js"></script>
    <%--
    The reason to use a JSP is that it is very easy to obtain server-side configuration
    information (such as the contextPath) and pass it to the JavaScript environment on the client.
    --%>
    <script type="text/javascript">
        var config = {
            contextPath: "${pageContext.request.contextPath}"
        };
    </script>
</head>
<body>

    <div id="body"></div>

</body>
</html>
