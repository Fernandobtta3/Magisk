package com.topjohnwu.magisk.redesign.superuser

import android.content.pm.PackageManager
import android.content.res.Resources
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.data.database.PolicyDao
import com.topjohnwu.magisk.databinding.ComparableRvItem
import com.topjohnwu.magisk.extensions.applySchedulers
import com.topjohnwu.magisk.extensions.subscribeK
import com.topjohnwu.magisk.extensions.toggle
import com.topjohnwu.magisk.model.entity.MagiskPolicy
import com.topjohnwu.magisk.model.entity.recycler.PolicyItem
import com.topjohnwu.magisk.model.entity.recycler.TappableHeadlineItem
import com.topjohnwu.magisk.model.events.PolicyUpdateEvent
import com.topjohnwu.magisk.model.events.SnackbarEvent
import com.topjohnwu.magisk.model.events.dialog.BiometricDialog
import com.topjohnwu.magisk.model.events.dialog.SuperuserRevokeDialog
import com.topjohnwu.magisk.model.navigation.Navigation
import com.topjohnwu.magisk.redesign.compat.CompatViewModel
import com.topjohnwu.magisk.redesign.compat.diffListOf
import com.topjohnwu.magisk.redesign.compat.itemBindingOf
import com.topjohnwu.magisk.utils.BiometricHelper
import com.topjohnwu.magisk.utils.currentLocale
import io.reactivex.Single

class SuperuserViewModel(
    private val db: PolicyDao,
    private val packageManager: PackageManager,
    private val resources: Resources
) : CompatViewModel(), TappableHeadlineItem.Listener {

    val items = diffListOf<ComparableRvItem<*>>()
    val itemBinding = itemBindingOf<ComparableRvItem<*>> {
        it.bindExtra(BR.viewModel, this)
        it.bindExtra(BR.listener, this)
    }

    companion object {
        private val menuOptions = listOf(
            TappableHeadlineItem.Hide,
            TappableHeadlineItem.Safetynet
        )
    }

    // ---

    override fun refresh() = db.fetchAll()
        .flattenAsFlowable { it }
        .parallel()
        .map { PolicyItem(it, it.applicationInfo.loadIcon(packageManager)) }
        .sequential()
        .sorted { o1, o2 ->
            compareBy<PolicyItem>(
                { it.item.appName.toLowerCase(currentLocale) },
                { it.item.packageName }
            ).compare(o1, o2)
        }
        .toList()
        .map { menuOptions + it }
        .map { it to items.calculateDiff(it) }
        .applySchedulers()
        .applyViewModel(this)
        .subscribeK { items.update(it.first, it.second) }

    // ---

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    override fun onItemPressed(item: TappableHeadlineItem) = when (item) {
        TappableHeadlineItem.Hide -> hidePressed()
        TappableHeadlineItem.Safetynet -> safetynetPressed()
        else -> Unit
    }

    private fun safetynetPressed() = Navigation.safetynet().publish()
    private fun hidePressed() = Navigation.hide().publish()

    fun deletePressed(item: PolicyItem) {
        fun updateState() = deletePolicy(item.item)
            .subscribeK { items.removeAll { it.genericItemSameAs(item) } }
            .add()

        if (BiometricHelper.isEnabled) {
            BiometricDialog {
                onSuccess { updateState() }
            }.publish()
        } else {
            SuperuserRevokeDialog {
                appName = item.item.appName
                onSuccess { updateState() }
            }.publish()
        }
    }

    //---

    fun updatePolicy(it: PolicyUpdateEvent) = when (it) {
        is PolicyUpdateEvent.Notification -> updatePolicy(it.item).map {
            when {
                it.notification -> R.string.su_snack_notif_on
                else -> R.string.su_snack_notif_off
            } to it.appName
        }
        is PolicyUpdateEvent.Log -> updatePolicy(it.item).map {
            when {
                it.logging -> R.string.su_snack_log_on
                else -> R.string.su_snack_log_off
            } to it.appName
        }
    }.map { resources.getString(it.first, it.second) }
        .subscribeK { SnackbarEvent(it).publish() }
        .add()

    fun togglePolicy(item: PolicyItem, enable: Boolean) {
        fun updateState() {
            val policy = if (enable) MagiskPolicy.ALLOW else MagiskPolicy.DENY
            val app = item.item.copy(policy = policy)

            updatePolicy(app)
                .map { it.policy == MagiskPolicy.ALLOW }
                .map { if (it) R.string.su_snack_grant else R.string.su_snack_deny }
                .map { resources.getString(it).format(item.item.appName) }
                .subscribeK { SnackbarEvent(it).publish() }
                .add()
        }

        if (BiometricHelper.isEnabled) {
            BiometricDialog {
                onSuccess { updateState() }
                onFailure { item.isEnabled.toggle() }
            }.publish()
        } else {
            updateState()
        }
    }

    //---

    private fun updatePolicy(policy: MagiskPolicy) =
        db.update(policy).andThen(Single.just(policy))

    private fun deletePolicy(policy: MagiskPolicy) =
        db.delete(policy.uid).andThen(Single.just(policy))

}