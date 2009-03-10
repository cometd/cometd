package org.mortbay;

import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

/**
 * @version $Revision: 1361 $ $Date: 2008-12-16 11:59:27 +0100 (Tue, 16 Dec 2008) $
 */
public class TestNGListener implements ITestListener
{
    private static final String EOL = System.getProperty("line.separator");

    public void onTestStart(ITestResult testResult)
    {
    }

    public void onTestSuccess(ITestResult testResult)
    {
        System.out.println("passed " + testResult.getTestClass().getRealClass().getSimpleName() + "." + testResult.getName() + EOL);
    }

    public void onTestFailure(ITestResult testResult)
    {
        System.out.println("failed " + testResult.getTestClass().getRealClass().getSimpleName() + "."  + testResult.getName() + EOL);
        testResult.getThrowable().printStackTrace(System.out);
    }

    public void onTestSkipped(ITestResult testResult)
    {
        System.out.println("skipped " + testResult.getTestClass().getRealClass().getSimpleName() + "."  + testResult.getName() + EOL);
    }

    public void onTestFailedButWithinSuccessPercentage(ITestResult testResult)
    {
    }

    public void onStart(ITestContext testContext)
    {
    }

    public void onFinish(ITestContext testContext)
    {
    }
}
