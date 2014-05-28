#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
<!DOCTYPE html>
<html>
<head>
    <meta http-equiv="Content-Type" content="text/html;charset=utf-8" />
    <script data-dojo-config="async: true, deps: ['application.js'], tlmSiblingOfDojo: true" src="${symbol_dollar}{pageContext.request.contextPath}/dojo/dojo.js.uncompressed.js"></script>
    <%--
    The reason to use a JSP is that it is very easy to obtain server-side configuration
    information (such as the contextPath) and pass it to the JavaScript environment on the client.
    --%>
    <script type="text/javascript">
        var config = {
            contextPath: '${symbol_dollar}{pageContext.request.contextPath}'
        };
    </script>
</head>
<body>

    <div id="body"></div>

</body>
</html>
