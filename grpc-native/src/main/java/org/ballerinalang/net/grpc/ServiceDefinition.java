/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.ballerinalang.net.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.InvalidProtocolBufferException;
import io.ballerina.runtime.api.PredefinedTypes;
import io.ballerina.runtime.api.TypeTags;
import io.ballerina.runtime.api.types.AttachedFunctionType;
import io.ballerina.runtime.api.types.ObjectType;
import io.ballerina.runtime.api.types.TupleType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.types.UnionType;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import org.ballerinalang.net.grpc.exception.GrpcClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.ballerinalang.net.grpc.GrpcConstants.PROTOCOL_PACKAGE_GRPC;
import static org.ballerinalang.net.grpc.MessageUtils.setNestedMessages;
import static org.ballerinalang.net.grpc.MethodDescriptor.generateFullMethodName;
import static org.ballerinalang.net.grpc.ServicesBuilderUtils.getBallerinaValueType;
import static org.ballerinalang.net.grpc.ServicesBuilderUtils.hexStringToByteArray;

/**
 * This class contains proto descriptors of the service.
 *
 * @since 0.980.0
 */
public final class ServiceDefinition {

    private String rootDescriptor;
    private BMap<BString, Object> descriptorMap;
    private Descriptors.FileDescriptor fileDescriptor;

    public ServiceDefinition(String rootDescriptor, BMap<BString, Object> descriptorMap) {
        this.rootDescriptor = rootDescriptor;
        this.descriptorMap = descriptorMap;
    }
    
    /**
     * Returns file descriptor of the gRPC service.
     *
     * @return file descriptor of the service.
     * @throws GrpcClientException if an error occur while generating service descriptor.
     */
    public Descriptors.FileDescriptor getDescriptor() throws GrpcClientException {
        if (fileDescriptor != null) {
            return fileDescriptor;
        }
        try {
            return fileDescriptor = getFileDescriptor(rootDescriptor, descriptorMap);
        } catch (IOException | Descriptors.DescriptorValidationException e) {
            throw new GrpcClientException("Error while generating service descriptor : ", e);
        }
    }

    private Descriptors.FileDescriptor getFileDescriptor(String rootDescriptor, BMap<BString, Object> descriptorMap)
            throws InvalidProtocolBufferException, Descriptors.DescriptorValidationException, GrpcClientException {
        byte[] descriptor = hexStringToByteArray(rootDescriptor);
        if (descriptor.length == 0) {
            throw new GrpcClientException("Error while reading the service proto descriptor. input descriptor " +
                                                  "string is null.");
        }
        DescriptorProtos.FileDescriptorProto descriptorProto = DescriptorProtos.FileDescriptorProto.parseFrom
                (descriptor);
        if (descriptorProto == null) {
            throw new GrpcClientException("Error while reading the service proto descriptor. File proto descriptor" +
                    " is null.");
        }
        Descriptors.FileDescriptor[] fileDescriptors = new Descriptors.FileDescriptor[descriptorProto
                .getDependencyList().size()];
        int i = 0;
        for (ByteString dependency : descriptorProto.getDependencyList().asByteStringList()) {
            if (descriptorMap.containsKey(StringUtils.fromString(dependency.toStringUtf8()))) {
                BString bRootDescriptor = (BString) descriptorMap
                        .get(StringUtils.fromString(dependency.toString(StandardCharsets.UTF_8)));
                fileDescriptors[i++] =
                        getFileDescriptor(bRootDescriptor.getValue() , descriptorMap);
            }
        }
        if (fileDescriptors.length > 0 && i == 0) {
            throw new GrpcClientException("Error while reading the service proto descriptor. Couldn't find any " +
                    "dependent descriptors.");
        }
        return Descriptors.FileDescriptor.buildFrom(descriptorProto, fileDescriptors);
    }

    private Descriptors.ServiceDescriptor getServiceDescriptor(String clientTypeName) throws GrpcClientException {
        Descriptors.FileDescriptor descriptor = getDescriptor();

        if (descriptor.getFile().getServices().isEmpty()) {
            throw new GrpcClientException("No service found in proto definition file");
        }

        Descriptors.ServiceDescriptor serviceDescriptor = null;
        String serviceName = null;
        if (clientTypeName != null) {
            if (clientTypeName.endsWith("BlockingClient")) {
                serviceName = clientTypeName.substring(0, clientTypeName.length() - 14);
            } else if (clientTypeName.endsWith("Client")) {
                serviceName = clientTypeName.substring(0, clientTypeName.length() - 6);
            }
        }
        if (serviceName != null) {
            serviceDescriptor = descriptor.findServiceByName(serviceName);
        }
        if (serviceDescriptor == null) {
            if (descriptor.getFile().getServices().size() == 1) {
                serviceDescriptor = descriptor.getFile().getServices().get(0);
            } else {
                throw new GrpcClientException("Couldn't find service descriptor for client endpoint in the proto " +
                        "definition. Please check client endpoint type name with the service name in the proto " +
                        "definition.");
            }
        }
        return serviceDescriptor;
    }

    public Map<String, MethodDescriptor> getMethodDescriptors(ObjectType clientEndpointType)
            throws GrpcClientException {
        Map<String, MethodDescriptor> descriptorMap = new HashMap<>();
        Descriptors.ServiceDescriptor serviceDescriptor = getServiceDescriptor(clientEndpointType.getName());
        AttachedFunctionType[] attachedFunctions = clientEndpointType.getAttachedFunctions();
        for (AttachedFunctionType attachedFunction : attachedFunctions) {
            String methodName = attachedFunction.getName();
            Descriptors.MethodDescriptor methodDescriptor = serviceDescriptor.findMethodByName(methodName);
            if (methodDescriptor == null) {
                throw new GrpcClientException("Error while initializing client stub. Couldn't find method descriptor " +
                        "for remote function: " + methodName);
            }
            Descriptors.Descriptor reqMessage = methodDescriptor.getInputType();
            Descriptors.Descriptor resMessage = methodDescriptor.getOutputType();
            MessageRegistry messageRegistry = MessageRegistry.getInstance();
            // update request message descriptors.
            messageRegistry.addMessageDescriptor(reqMessage.getName(), reqMessage);
            setNestedMessages(reqMessage, messageRegistry);
            // update response message descriptors
            messageRegistry.addMessageDescriptor(resMessage.getName(), resMessage);
            setNestedMessages(resMessage, messageRegistry);
            String fullMethodName = generateFullMethodName(serviceDescriptor.getFullName(), methodName);
            Type requestType = getInputParameterType(attachedFunction);
            Type responseType = getReturnParameterType(attachedFunction);
            MethodDescriptor descriptor =
                    MethodDescriptor.newBuilder()
                            .setType(MessageUtils.getMethodType(methodDescriptor.toProto()))
                            .setFullMethodName(fullMethodName)
                            .setRequestMarshaller(ProtoUtils.marshaller(new MessageParser(reqMessage.getName(),
                                    requestType)))
                            .setResponseMarshaller(ProtoUtils.marshaller(new MessageParser(resMessage.getName(),
                                    responseType == null ?
                                            getBallerinaValueType(clientEndpointType.getPackage(),
                                                    resMessage.getName()) : responseType)))
                            .setSchemaDescriptor(methodDescriptor)
                            .build();
            descriptorMap.put(fullMethodName, descriptor);
        }
        return Collections.unmodifiableMap(descriptorMap);
    }

    private Type getReturnParameterType(AttachedFunctionType attachedFunction) {
        Type functionReturnType = attachedFunction.getType().getReturnParameterType();
        if (functionReturnType.getTag() == TypeTags.UNION_TAG) {
            UnionType unionReturnType = (UnionType) functionReturnType;
            Type firstParamType = unionReturnType.getMemberTypes().get(0);
            if (firstParamType.getTag() == TypeTags.TUPLE_TAG) {
                TupleType tupleType = (TupleType) firstParamType;
                return tupleType.getTupleTypes().get(0);
            } else if ("Headers".equals(firstParamType.getName()) &&
                    firstParamType.getPackage() != null &&
                    PROTOCOL_PACKAGE_GRPC.equals(firstParamType.getPackage().getName())) {
                return PredefinedTypes.TYPE_NULL;
            }
        }
        return null;
    }

    private Type getInputParameterType(AttachedFunctionType attachedFunction) {
        Type[] inputParams = attachedFunction.getParameterTypes();
        if (inputParams.length > 0) {
            Type inputType = inputParams[0];
            if (inputType != null && "Headers".equals(inputType.getName()) &&
                    inputType.getPackage() != null && PROTOCOL_PACKAGE_GRPC.equals(inputType.getPackage().getName())) {
                return PredefinedTypes.TYPE_NULL;
            } else {
                return inputParams[0];
            }
        }
        return PredefinedTypes.TYPE_NULL;
    }
}
