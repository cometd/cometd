<!DOCTYPE html>
<html>
<head>
    <meta http-equiv="Content-Type" content="text/html;charset=utf-8" />
    <script data-dojo-config="async: true, deps: ['application.js'], tlmSiblingOfDojo: true" src="${pageContext.request.contextPath}/js/dojo/dojo.js.uncompressed.js"></script>
    <%--
    The reason to use a JSP is that it is very easy to obtain server-side configuration
    information (such as the contextPath) and pass it to the JavaScript environment on the client.
    --%>
    <script type="text/javascript">
        var config = {
            contextPath: '${pageContext.request.contextPath}'
        };
    </script>
</head>
<body>

    <div id="body"></div>

</body>
</html>
