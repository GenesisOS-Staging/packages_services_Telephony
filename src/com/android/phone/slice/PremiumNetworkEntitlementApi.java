/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone.slice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.PersistableBundle;
import android.provider.DeviceConfig;
import android.telephony.AnomalyReporter;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.libraries.entitlement.CarrierConfig;
import com.android.libraries.entitlement.ServiceEntitlement;
import com.android.libraries.entitlement.ServiceEntitlementException;
import com.android.libraries.entitlement.ServiceEntitlementRequest;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Premium network entitlement API class to check the premium network slice entitlement result
 * from carrier API over the network.
 */
public class PremiumNetworkEntitlementApi {
    private static final String TAG = "PremiumNwEntitlementApi";
    private static final String ENTITLEMENT_STATUS_KEY = "EntitlementStatus";
    private static final String PROVISION_STATUS_KEY = "ProvisionStatus";
    private static final String SERVICE_FLOW_URL_KEY = "ServiceFlow_URL";
    private static final String PROVISION_TIME_LEFT_KEY = "ProvisionTimeLeft";
    private static final String DEFAULT_EAP_AKA_RESPONSE = "Default EAP AKA response";
    /**
     * UUID to report an anomaly if an unexpected error is received during entitlement check.
     */
    private static final String UUID_ENTITLEMENT_CHECK_UNEXPECTED_ERROR =
            "f2b0661a-9114-4b1b-9add-a8d338f9c054";

    /**
     * Experiment flag to enable bypassing EAP-AKA authentication for Slice Purchase activities.
     * The device will accept any challenge from the entitlement server and return a predefined
     * string as a response.
     *
     * This flag should be enabled for testing only.
     */
    public static final String BYPASS_EAP_AKA_AUTH_FOR_SLICE_PURCHASE_ENABLED =
            "bypass_eap_aka_auth_for_slice_purchase_enabled";

    @NonNull private final Phone mPhone;
    @NonNull private final ServiceEntitlement mServiceEntitlement;

    public PremiumNetworkEntitlementApi(@NonNull Phone phone,
            @NonNull PersistableBundle carrierConfig) {
        mPhone = phone;
        if (isBypassEapAkaAuthForSlicePurchaseEnabled()) {
            mServiceEntitlement =
                    new ServiceEntitlement(
                            mPhone.getContext(),
                            getEntitlementServerCarrierConfig(carrierConfig),
                            mPhone.getSubId(),
                            true,
                            DEFAULT_EAP_AKA_RESPONSE);
        } else {
            mServiceEntitlement =
                    new ServiceEntitlement(
                            mPhone.getContext(),
                            getEntitlementServerCarrierConfig(carrierConfig),
                            mPhone.getSubId());
        }
    }

    /**
     * Returns premium network slice entitlement check result from carrier API (over network),
     * or {@code null} on unrecoverable network issue or malformed server response.
     * This is blocking call sending HTTP request and should not be called on main thread.
     */
    @Nullable public PremiumNetworkEntitlementResponse checkEntitlementStatus(
            @TelephonyManager.PremiumCapability int capability) {
        Log.d(TAG, "checkEntitlementStatus subId=" + mPhone.getSubId());
        ServiceEntitlementRequest.Builder requestBuilder = ServiceEntitlementRequest.builder();
        // Set fake device info to avoid leaking
        requestBuilder.setTerminalVendor("vendorX");
        requestBuilder.setTerminalModel("modelY");
        requestBuilder.setTerminalSoftwareVersion("versionZ");
        requestBuilder.setAcceptContentType(ServiceEntitlementRequest.ACCEPT_CONTENT_TYPE_JSON);
        requestBuilder.setNetworkIdentifier(
                TelephonyManager.convertPremiumCapabilityToString(capability));
        ServiceEntitlementRequest request = requestBuilder.build();
        PremiumNetworkEntitlementResponse premiumNetworkEntitlementResponse =
                new PremiumNetworkEntitlementResponse();

        String response = null;
        try {
            response = mServiceEntitlement.queryEntitlementStatus(
                    ServiceEntitlement.APP_PREMIUM_NETWORK_SLICE,
                    request);
        } catch (ServiceEntitlementException e) {
            Log.e(TAG, "queryEntitlementStatus failed", e);
            reportAnomaly(UUID_ENTITLEMENT_CHECK_UNEXPECTED_ERROR,
                    "checkEntitlementStatus failed with ServiceEntitlementException");
        }
        if (response == null) {
            return null;
        }
        try {
            JSONObject jsonAuthResponse = new JSONObject(response);
            String entitlementStatus = null;
            String provisionStatus = null;
            String provisionTimeLeft = null;
            if (jsonAuthResponse.has(ServiceEntitlement.APP_PREMIUM_NETWORK_SLICE)) {
                JSONObject jsonToken = jsonAuthResponse.getJSONObject(
                        ServiceEntitlement.APP_PREMIUM_NETWORK_SLICE);
                if (jsonToken.has(ENTITLEMENT_STATUS_KEY)) {
                    entitlementStatus = jsonToken.getString(ENTITLEMENT_STATUS_KEY);
                    if (entitlementStatus == null) {
                        return null;
                    }
                    premiumNetworkEntitlementResponse.mEntitlementStatus =
                            Integer.parseInt(entitlementStatus);
                }
                if (jsonToken.has(PROVISION_STATUS_KEY)) {
                    provisionStatus = jsonToken.getString(PROVISION_STATUS_KEY);
                    if (provisionStatus != null) {
                        premiumNetworkEntitlementResponse.mProvisionStatus =
                                Integer.parseInt(provisionStatus);
                    }
                }
                if (jsonToken.has(PROVISION_TIME_LEFT_KEY)) {
                    provisionTimeLeft = jsonToken.getString(PROVISION_TIME_LEFT_KEY);
                    if (provisionTimeLeft != null) {
                        premiumNetworkEntitlementResponse.mEntitlementStatus =
                                Integer.parseInt(provisionTimeLeft);
                    }
                }
                if (jsonToken.has(SERVICE_FLOW_URL_KEY)) {
                    provisionStatus = jsonToken.getString(SERVICE_FLOW_URL_KEY);
                    if (provisionStatus != null) {
                        premiumNetworkEntitlementResponse.mProvisionStatus =
                                Integer.parseInt(provisionStatus);
                    }
                    premiumNetworkEntitlementResponse.mServiceFlowURL =
                            jsonToken.getString(SERVICE_FLOW_URL_KEY);
                }
            }


        } catch (JSONException e) {
            Log.e(TAG, "queryEntitlementStatus failed", e);
            reportAnomaly(UUID_ENTITLEMENT_CHECK_UNEXPECTED_ERROR,
                    "checkEntitlementStatus failed with JSONException");
        } catch (NumberFormatException e) {
            Log.e(TAG, "queryEntitlementStatus failed", e);
            reportAnomaly(UUID_ENTITLEMENT_CHECK_UNEXPECTED_ERROR,
                    "checkEntitlementStatus failed with NumberFormatException");
        }

        return premiumNetworkEntitlementResponse;
    }

    private void reportAnomaly(@NonNull String uuid, @NonNull String log) {
        AnomalyReporter.reportAnomaly(UUID.fromString(uuid), log);
    }

    /**
     * Returns entitlement server url from the given carrier configs or a default empty string
     * if it is not available.
     */
    @NonNull public static String getEntitlementServerUrl(
            @NonNull PersistableBundle carrierConfig) {
        return carrierConfig.getString(
                CarrierConfigManager.ImsServiceEntitlement.KEY_ENTITLEMENT_SERVER_URL_STRING,
                "");
    }

    @NonNull private CarrierConfig getEntitlementServerCarrierConfig(
            @NonNull PersistableBundle carrierConfig) {
        String entitlementServiceUrl = getEntitlementServerUrl(carrierConfig);
        return CarrierConfig.builder().setServerUrl(entitlementServiceUrl).build();
    }

    private boolean isBypassEapAkaAuthForSlicePurchaseEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TELEPHONY,
                BYPASS_EAP_AKA_AUTH_FOR_SLICE_PURCHASE_ENABLED, false);
    }
}