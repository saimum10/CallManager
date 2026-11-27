package com.saimum.callmanager.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager

/**
 * One physical SIM slot. [slotIndex] is 0-based (UI shows it as
 * "SIM ${slotIndex + 1}"); [subscriptionId] is what TelephonyManager needs
 * (via createForSubscriptionId) to target this specific SIM for both
 * dialing and status listening.
 */
data class SimSlotInfo(
    val slotIndex: Int,
    val subscriptionId: Int,
    val displayName: String
)

/**
 * Resolves the device's active SIM slots. Caller must hold READ_PHONE_STATE.
 */
class SimSlotProvider(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun getActiveSlots(): List<SimSlotInfo> {
        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

        val activeSubscriptions = try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

        return activeSubscriptions
            .sortedBy { it.simSlotIndex }
            .map { subscriptionInfo ->
                SimSlotInfo(
                    slotIndex = subscriptionInfo.simSlotIndex,
                    subscriptionId = subscriptionInfo.subscriptionId,
                    displayName = subscriptionInfo.displayName?.toString().orEmpty()
                )
            }
    }
}
