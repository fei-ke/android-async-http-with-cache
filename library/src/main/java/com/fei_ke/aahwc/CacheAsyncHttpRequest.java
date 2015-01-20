package com.fei_ke.aahwc;

import android.text.TextUtils;
import android.util.Log;

import com.loopj.android.http.AsyncHttpRequest;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.protocol.HttpContext;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.UnknownHostException;

/**
 * Created by fei-ke on 2015/1/20.
 */
public class CacheAsyncHttpRequest extends AsyncHttpRequest implements Runnable {
    private final AbstractHttpClient client;
    private final HttpContext context;
    private final HttpUriRequest request;
    private final CacheTextResponseHandler responseHandler;
    private int executionCount;
    private boolean isCancelled = false;
    private boolean cancelIsNotified = false;
    private boolean isFinished = false;

    //缓存相关
    private String cacheKey;
    private boolean isCacheAble;
    private int cacheTime = 0;//保存时间，单位秒,默认0永久保存
    private File cacheDir;//缓存目录

    public CacheAsyncHttpRequest(AbstractHttpClient client, HttpContext context, HttpUriRequest request,
                                 CacheTextResponseHandler responseHandler, boolean isCacheAble, String cacheKey, File cacheDir, int cacheTime) {
        super(client, context, request, responseHandler);
        this.client = client;
        this.context = context;
        this.request = request;
        this.responseHandler = responseHandler;

        //
        this.isCacheAble = isCacheAble;
        this.cacheDir = cacheDir;
        this.cacheTime = cacheTime;
        this.cacheKey = TextUtils.isEmpty(cacheKey) ? request.getURI().toString() : cacheKey;
    }

    @Override
    public void run() {
        if (isCancelled()) {
            return;
        }

        if (responseHandler != null) {
            responseHandler.sendStartMessage();
        }

        if (isCancelled()) {
            return;
        }

        try {
            makeRequestWithRetries();
        } catch (IOException e) {
            if (!isCancelled() && responseHandler != null) {
                responseHandler.sendFailureMessage(0, null, null, e);
            } else {
                Log.e("AsyncHttpRequest", "makeRequestWithRetries returned error, but handler is null", e);
            }
        }

        if (isCancelled()) {
            return;
        }

        if (responseHandler != null) {
            responseHandler.sendFinishMessage();
        }

        isFinished = true;
    }

    private void makeRequest() throws IOException {
        if (isCancelled()) {
            return;
        }
        // Fixes #115
        if (request.getURI().getScheme() == null) {
            // subclass of IOException so processed in the caller
            throw new MalformedURLException("No valid URI scheme was provided");
        }

        // ---------读取缓存------------------------
        if (isCacheAble) {
            ACache aCache = ACache.get(cacheDir);
            if (BuildConfig.DEBUG) {
                Log.i("读缓存 key:", "" + cacheKey);
            }
            byte[] cache = aCache.getAsBinary(cacheKey);
            if (cache != null) {
                responseHandler.sendCacheMessage(cache);
            }
        }
        // ---------读取缓存结束------------------------

        HttpResponse response = client.execute(request, context);

        //------------- 写缓存--------------
        if (!isCancelled() && responseHandler != null) {
            byte[] cache = responseHandler.sendResponseMessageWithCache(response);
            if (isCacheAble && cache != null) {
                ACache aCache = ACache.get(cacheDir);
                if (BuildConfig.DEBUG) {
                    Log.i("写缓存 key:", "" + cacheKey);
                }
                if (cacheTime == 0) {
                    aCache.put(cacheKey, cache);
                } else {
                    aCache.put(cacheKey, cache, cacheTime);
                }
            }
        }
        //------------- 写缓存结束-------------

    }

    private void makeRequestWithRetries() throws IOException {
        boolean retry = true;
        IOException cause = null;
        HttpRequestRetryHandler retryHandler = client.getHttpRequestRetryHandler();
        try {
            while (retry) {
                try {
                    makeRequest();
                    return;
                } catch (UnknownHostException e) {
                    // switching between WI-FI and mobile data networks can cause a retry which then results in an UnknownHostException
                    // while the WI-FI is initialising. The retry logic will be invoked here, if this is NOT the first retry
                    // (to assist in genuine cases of unknown host) which seems better than outright failure
                    cause = new IOException("UnknownHostException exception: " + e.getMessage());
                    retry = (executionCount > 0) && retryHandler.retryRequest(cause, ++executionCount, context);
                } catch (NullPointerException e) {
                    // there's a bug in HttpClient 4.0.x that on some occasions causes
                    // DefaultRequestExecutor to throw an NPE, see
                    // http://code.google.com/p/android/issues/detail?id=5255
                    cause = new IOException("NPE in HttpClient: " + e.getMessage());
                    retry = retryHandler.retryRequest(cause, ++executionCount, context);
                } catch (IOException e) {
                    if (isCancelled()) {
                        // Eating exception, as the request was cancelled
                        return;
                    }
                    cause = e;
                    retry = retryHandler.retryRequest(cause, ++executionCount, context);
                }
                if (retry && (responseHandler != null)) {
                    responseHandler.sendRetryMessage(executionCount);
                }
            }
        } catch (Exception e) {
            // catch anything else to ensure failure message is propagated
            Log.e("AsyncHttpRequest", "Unhandled exception origin cause", e);
            cause = new IOException("Unhandled exception: " + e.getMessage());
        }

        // cleaned up to throw IOException
        throw (cause);
    }

    public boolean isCancelled() {
        if (isCancelled) {
            sendCancelNotification();
        }
        return isCancelled;
    }

    private synchronized void sendCancelNotification() {
        if (!isFinished && isCancelled && !cancelIsNotified) {
            cancelIsNotified = true;
            if (responseHandler != null)
                responseHandler.sendCancelMessage();
        }
    }

    public boolean isDone() {
        return isCancelled() || isFinished;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        isCancelled = true;
        request.abort();
        return isCancelled();
    }
}
