package app.aaps.ui.dialogs.barcode

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.ui.dialogs.AlertDialogHelper
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogBarcodePortionBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.text.DecimalFormat

/**
 * Scan a food barcode, look it up in Open Food Facts, ask the user how much they eat,
 * and hand the resulting carbs back to the hosting dialog.
 *
 * Safety: this only ever PRE-FILLS the carbs field of the hosting dialog. Nothing is
 * submitted, and no bolus is triggered; the user still reviews and confirms the dialog.
 *
 * Must be created as a field of the fragment (before it reaches CREATED), because it
 * registers an activity-result launcher.
 */
class BarcodeCarbsHelper(private val fragment: Fragment) {

    fun interface CarbsCallback {

        /** @param carbs whole grams to ADD to the current value, @param note short description for the notes field */
        fun onCarbs(carbs: Int, note: String)
    }

    private val launcher = fragment.registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { onScanned(it.trim()) }
    }

    private var schedulers: AapsSchedulers? = null
    private var disposable: CompositeDisposable? = null
    private var maxCarbs: () -> Int = { 0 }
    private var callback: CarbsCallback? = null
    private val format = DecimalFormat("0.#")

    /** Call from onViewCreated, so it is also set again after the fragment is recreated. */
    fun setup(schedulers: AapsSchedulers, disposable: CompositeDisposable, maxCarbs: () -> Int, callback: CarbsCallback) {
        this.schedulers = schedulers
        this.disposable = disposable
        this.maxCarbs = maxCarbs
        this.callback = callback
    }

    fun scan() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.EAN_13, ScanOptions.EAN_8, ScanOptions.UPC_A, ScanOptions.UPC_E)
            .setPrompt(fragment.getString(R.string.barcode_scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        launcher.launch(options)
    }

    private fun onScanned(code: String) {
        val context = fragment.context ?: return
        val schedulers = schedulers ?: return
        if (!OpenFoodFactsParser.isValidBarcode(code)) {
            ToastUtils.warnToast(context, context.getString(R.string.barcode_invalid, code))
            return
        }
        ToastUtils.infoToast(context, R.string.barcode_looking_up)
        disposable?.let { d ->
            d += Single.fromCallable { OpenFoodFactsClient.lookup(code) }
                .subscribeOn(schedulers.io)
                .observeOn(schedulers.main)
                .subscribe({ handleResult(it) }, { handleResult(LookupResult.Error(it)) })
        }
    }

    private fun handleResult(result: LookupResult) {
        // The hosting dialog may have been closed while the lookup was running.
        if (fragment.view == null) return
        val context = fragment.context ?: return
        when (result) {
            is LookupResult.Found           -> showPortionDialog(context, result.product)
            is LookupResult.NotFound        -> ToastUtils.warnToast(context, context.getString(R.string.barcode_not_found, result.barcode))
            is LookupResult.NoCarbData      -> ToastUtils.warnToast(context, context.getString(R.string.barcode_no_carb_data, result.name))
            is LookupResult.InvalidCarbData -> ToastUtils.warnToast(context, context.getString(R.string.barcode_invalid_carb_data, result.name, format.format(result.value)))
            is LookupResult.Error           -> ToastUtils.warnToast(context, R.string.barcode_lookup_failed)
        }
    }

    private fun showPortionDialog(context: Context, product: FoodProduct) {
        val binding = DialogBarcodePortionBinding.inflate(LayoutInflater.from(context))
        val max = maxCarbs()

        binding.productName.text = product.name
        binding.carbsPer100.text = context.getString(R.string.barcode_carbs_per_100, format.format(product.carbsPer100g))
        if (product.sugarsExceedCarbs) {
            binding.warning.text = context.getString(R.string.barcode_warning_sugars, format.format(product.sugarsPer100g), format.format(product.carbsPer100g))
            binding.warning.visibility = View.VISIBLE
        }
        product.servingGrams?.let { serving ->
            binding.oneServing.text = context.getString(R.string.barcode_one_serving, format.format(serving))
            binding.oneServing.visibility = View.VISIBLE
            binding.oneServing.setOnClickListener { binding.grams.setText(format.format(serving).replace(',', '.')) }
        }

        val dialog = MaterialAlertDialogBuilder(context, app.aaps.core.ui.R.style.DialogTheme)
            .setCustomTitle(AlertDialogHelper.buildCustomTitle(context, context.getString(R.string.barcode_scan)))
            .setView(binding.root)
            .setPositiveButton(R.string.barcode_add) { _, _ ->
                val carbs = product.carbsFor(gramsOf(binding))
                if (carbs in 1..max) {
                    val note = context.getString(R.string.barcode_note, product.name, format.format(gramsOf(binding)))
                    callback?.onCarbs(carbs, note)
                }
            }
            .setNegativeButton(app.aaps.core.ui.R.string.cancel, null)
            .create()

        fun refresh() {
            val carbs = product.carbsFor(gramsOf(binding))
            val ok = carbs in 1..max
            binding.result.text =
                if (carbs > max) context.getString(R.string.barcode_result_too_high, carbs, max)
                else context.getString(R.string.barcode_result, carbs)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = ok
        }

        binding.grams.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        dialog.setOnShowListener {
            // Pre-fill with one serving if known; the user must still confirm the amount.
            binding.grams.setText(product.servingGrams?.let { format.format(it).replace(',', '.') } ?: "")
            binding.grams.requestFocus()
            refresh()
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun gramsOf(binding: DialogBarcodePortionBinding): Double =
        binding.grams.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 5000.0 } ?: 0.0
}
