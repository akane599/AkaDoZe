package com.akylas.enforcedoze

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import androidx.preference.ListPreferenceDialogFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MaterialListPreference : ListPreferenceDialogFragmentCompat() {
    private var mWhichButtonClicked = 0
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context: Context? = activity
        mWhichButtonClicked = DialogInterface.BUTTON_NEGATIVE
        val builder = MaterialAlertDialogBuilder(requireActivity()).setTitle(preference.dialogTitle).setIcon(preference.dialogIcon)
            .setPositiveButton(preference.positiveButtonText, this)
            .setNegativeButton(preference.negativeButtonText, this)
        val contentView = context?.let { onCreateDialogView(it) }
        if (contentView != null) {
            onBindDialogView(contentView)
            builder.setView(contentView)
        } else {
            builder.setMessage(preference.dialogMessage)
        }
        onPrepareDialogBuilder(builder)
        return builder.create()
    }

    override fun onClick(dialog: DialogInterface, which: Int) {
        mWhichButtonClicked = which
    }

    override fun onDismiss(dialog: DialogInterface) {
        onDialogClosedWasCalledFromOnDismiss = true
        super.onDismiss(dialog)
    }

    private var onDialogClosedWasCalledFromOnDismiss = false

    override fun onDialogClosed(positiveResult: Boolean) {
        if (onDialogClosedWasCalledFromOnDismiss) {
            onDialogClosedWasCalledFromOnDismiss = false
            super.onDialogClosed(mWhichButtonClicked == DialogInterface.BUTTON_POSITIVE)
        } else {
            super.onDialogClosed(positiveResult)
        }
    }
}