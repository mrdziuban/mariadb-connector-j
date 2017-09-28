/*
 *
 * MariaDB Client for Java
 *
 * Copyright (c) 2012-2014 Monty Program Ab.
 * Copyright (c) 2015-2017 MariaDB Ab.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to Monty Program Ab info@montyprogram.com.
 *
 * This particular MariaDB Client for Java file is work
 * derived from a Drizzle-JDBC. Drizzle-JDBC file which is covered by subject to
 * the following copyright and notice provisions:
 *
 * Copyright (c) 2009-2011, Marcus Eriksson
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this list
 * of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * Neither the name of the driver nor the names of its contributors may not be
 * used to endorse or promote products derived from this software without specific
 * prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS  AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 *
 */

package org.mariadb.jdbc.internal.logging;

import org.mariadb.jdbc.internal.com.send.parameters.ParameterHolder;
import org.mariadb.jdbc.internal.protocol.Protocol;
import org.mariadb.jdbc.internal.util.LogQueryTool;
import org.mariadb.jdbc.internal.util.Options;
import org.mariadb.jdbc.internal.util.dao.ClientPrepareResult;
import org.mariadb.jdbc.internal.util.dao.PrepareResult;
import org.mariadb.jdbc.internal.util.dao.ServerPrepareResult;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.List;

public class ProtocolLoggingProxy implements InvocationHandler {
    private static final NumberFormat numberFormat = DecimalFormat.getInstance();
    private static final Logger logger = LoggerFactory.getLogger(ProtocolLoggingProxy.class);
    private final boolean profileSql;
    private final Long slowQueryThresholdNanos;
    private final int maxQuerySizeToLog;
    private final Protocol protocol;
    private final LogQueryTool logQuery;

    /**
     * Constructor. Will create a proxy around protocol to log queries.
     *
     * @param protocol protocol to proxy
     * @param options  options
     */
    public ProtocolLoggingProxy(Protocol protocol, Options options) {
        this.protocol = protocol;
        this.profileSql = options.profileSql;
        this.slowQueryThresholdNanos = options.slowQueryThresholdNanos;
        this.maxQuerySizeToLog = options.maxQuerySizeToLog;
        this.logQuery = new LogQueryTool(options);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        long startTime = System.nanoTime();
        try {
            if ("executeQuery".equals(method.getName())
                    || "executePreparedQuery".equals(method.getName())
                    || "executeBatchStmt".equals(method.getName())
                    || "executeBatchClient".equals(method.getName())
                    || "executeBatchServer".equals(method.getName())) {

                Object returnObj = method.invoke(protocol, args);
                if (logger.isInfoEnabled() && (profileSql
                        || (slowQueryThresholdNanos != null && System.nanoTime() - startTime > slowQueryThresholdNanos))) {

                    String sql = logQuery(method.getName(), args);
                    logger.info("conn={}({}) - {} ms - Query: {}",
                            protocol.getServerThreadId(),
                            protocol.isMasterConnection() ? "M" : "S",
                            numberFormat.format(((double) System.nanoTime() - startTime) / 1000000),
                            logQuery.subQuery(sql));
                }
                return returnObj;
            } else {
                return method.invoke(protocol, args);
            }

        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private String logQuery(String methodName, Object[] args) {
        if ("executeQuery".equals(methodName)) {
            switch (args.length) {
                case 1:
                    return (String) args[0];
                case 3:
                    return (String) args[2];
                case 4:
                case 5:
                    if (Charset.class.isInstance(args[3])) {
                        return (String) args[2];
                    }
                    ClientPrepareResult clientPrepareResult = (ClientPrepareResult) args[2];
                    return getQueryFromPrepareParameters(
                            clientPrepareResult,
                            (ParameterHolder[]) args[3],
                            clientPrepareResult.getParamCount());
                default:
                    //no default
            }

        } else if ("executeBatchClient".equals(methodName)) {
            ClientPrepareResult clientPrepareResult = (ClientPrepareResult) args[2];
            return getQueryFromPrepareParameters(clientPrepareResult.getSql(), (List<ParameterHolder[]>) args[3],
                    clientPrepareResult.getParamCount());

        } else if ("executeBatchStmt".equals(methodName)) {
            List<String> multipleQueries = (List<String>) args[2];
            if (multipleQueries.size() == 1) {
                return multipleQueries.get(0);
            } else {
                StringBuilder sb = new StringBuilder();
                for (String multipleQuery : multipleQueries) {
                    if (maxQuerySizeToLog > 0 && (sb.length() + multipleQuery.length() + 1) > maxQuerySizeToLog) {
                        sb.append(multipleQuery.substring(1, Math.max(1, maxQuerySizeToLog - sb.length())));
                        break;
                    }
                    sb.append(multipleQuery).append(";");
                    if (maxQuerySizeToLog > 0 && sb.length() >= maxQuerySizeToLog) break;
                }
                return sb.toString();
            }

        } else if ("executeBatchServer".equals(methodName)) {
            List<ParameterHolder[]> parameterList = (List<ParameterHolder[]>) args[4];
            ServerPrepareResult serverPrepareResult = (ServerPrepareResult) args[1];
            return getQueryFromPrepareParameters(serverPrepareResult.getSql(), parameterList, serverPrepareResult.getParamCount());

        } else if ("executePreparedQuery".equals(methodName)) {
            ServerPrepareResult prepareResult = (ServerPrepareResult) args[1];
            if (args[3] instanceof ParameterHolder[]) {
                return getQueryFromPrepareParameters(prepareResult, (ParameterHolder[]) args[3], prepareResult.getParamCount());
            } else {
                return getQueryFromPrepareParameters(prepareResult.getSql(), (List<ParameterHolder[]>) args[3],
                        prepareResult.getParameters().length);
            }
        }
        return "-unknown-";
    }

    private String getQueryFromPrepareParameters(String sql, List<ParameterHolder[]> parameterList, int parameterLength) {

        if (parameterLength == 0) {
            return sql;
        } else {
            StringBuilder sb = new StringBuilder(sql).append(", parameters ");
            for (int paramNo = 0; paramNo < parameterList.size(); paramNo++) {
                ParameterHolder[] parameters = parameterList.get(paramNo);

                if (paramNo != 0) sb.append(",");
                sb.append("[");
                for (int i = 0; i < parameterLength; i++) {
                    if (i != 0) sb.append(",");
                    sb.append(parameters[i].toString());
                }
                if (maxQuerySizeToLog > 0 && sb.length() > maxQuerySizeToLog) {
                    break;
                } else {
                    sb.append("]");
                }
            }
            return sb.toString();
        }
    }

    private String getQueryFromPrepareParameters(PrepareResult serverPrepareResult, ParameterHolder[] paramHolders, int parameterLength) {
        StringBuilder sb = new StringBuilder(serverPrepareResult.getSql());
        if (paramHolders.length > 0) {
            sb.append(", parameters [");
            for (int i = 0; i < parameterLength; i++) {
                if (i != 0) sb.append(",");
                sb.append(paramHolders[i].toString());
                if (maxQuerySizeToLog > 0 && sb.length() > maxQuerySizeToLog) break;
            }
            return sb.append("]").toString();
        }
        return serverPrepareResult.getSql();
    }

}
