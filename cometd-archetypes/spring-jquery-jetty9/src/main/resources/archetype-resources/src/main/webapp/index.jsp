#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
<!DOCTYPE html>
<html>
<head>
    <meta http-equiv="Content-Type" content="text/html;charset=utf-8" />
    <script type="text/javascript" src="https://code.jquery.com/jquery-3.4.1.js"></script>
    <script type="text/javascript" src="${symbol_dollar}{pageContext.request.contextPath}/js/cometd/cometd.js"></script>
    <script type="text/javascript" src="${symbol_dollar}{pageContext.request.contextPath}/js/jquery/jquery.cometd.js"></script>
    <script type="text/javascript" src="application.js"></script>
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
