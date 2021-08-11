package org.ergoplatform.android.transactions

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ergoplatform.android.*
import org.ergoplatform.android.ui.PasswordDialogFragment
import org.ergoplatform.android.ui.SingleLiveEvent
import org.ergoplatform.android.wallet.ENC_TYPE_DEVICE
import org.ergoplatform.android.wallet.ENC_TYPE_PASSWORD
import org.ergoplatform.android.wallet.WalletConfigDbEntity
import org.ergoplatform.android.wallet.WalletTokenDbEntity
import org.ergoplatform.api.AesEncryptionManager
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.ErgoToken
import org.ergoplatform.appkit.Parameters

/**
 * Holding state of the send funds screen (thus to be expected to get complicated)
 */
class SendFundsViewModel : ViewModel() {
    var wallet: WalletConfigDbEntity? = null
        private set

    var receiverAddress: String = ""
        set(value) {
            field = value
            calcGrossAmount()
        }
    var amountToSend: Float = 0f
        set(value) {
            field = value
            calcGrossAmount()
        }

    private val _lockInterface = MutableLiveData<Boolean>()
    val lockInterface: LiveData<Boolean> = _lockInterface
    private val _walletName = MutableLiveData<String>()
    val walletName: LiveData<String> = _walletName
    private val _walletBalance = MutableLiveData<Float>()
    val walletBalance: LiveData<Float> = _walletBalance
    private val _feeAmount = MutableLiveData<Float>().apply {
        value = nanoErgsToErgs(Parameters.MinFee)
    }
    val feeAmount: LiveData<Float> = _feeAmount
    private val _grossAmount = MutableLiveData<Float>().apply {
        value = 0f
    }
    val grossAmount: LiveData<Float> = _grossAmount
    private val _paymentDoneLiveData = SingleLiveEvent<TransactionResult>()
    val paymentDoneLiveData: LiveData<TransactionResult> = _paymentDoneLiveData
    private val _txId = MutableLiveData<String>()
    val txId: LiveData<String> = _txId

    val tokensAvail: ArrayList<WalletTokenDbEntity> = ArrayList()
    val tokensChosen: ArrayList<ErgoToken> = ArrayList()

    // the live data gets data posted on adding or removing tokens, not on every amount change
    private val _tokensChosenLiveData = MutableLiveData<List<ErgoToken>>()
    val tokensChosenLiveData: LiveData<List<ErgoToken>> = _tokensChosenLiveData

    fun initWallet(ctx: Context, walletId: Int) {
        viewModelScope.launch {
            val walletWithState =
                AppDatabase.getInstance(ctx).walletDao().loadWalletWithStateById(walletId)
            wallet = walletWithState?.walletConfig

            wallet?.displayName?.let {
                _walletName.postValue(it)
            }
            walletWithState?.state?.map { it.balance ?: 0 }?.sum()
                ?.let { _walletBalance.postValue(nanoErgsToErgs(it)) }
            tokensAvail.clear()
            walletWithState?.tokens?.let { tokensAvail.addAll(it) }
            _tokensChosenLiveData.postValue(tokensChosen)
        }
        calcGrossAmount()
    }

    private fun calcGrossAmount() {
        _grossAmount.postValue(feeAmount.value!! + amountToSend)
    }

    fun checkReceiverAddress(): Boolean {
        return isValidErgoAddress(receiverAddress)
    }

    fun checkAmount(): Boolean {
        return amountToSend >= nanoErgsToErgs(Parameters.MinChangeValue)
    }

    fun preparePayment(fragment: SendFundsFragmentDialog) {
        if (wallet?.encryptionType == ENC_TYPE_PASSWORD) {
            PasswordDialogFragment().show(
                fragment.childFragmentManager,
                null
            )
        } else if (wallet?.encryptionType == ENC_TYPE_DEVICE) {
            fragment.showBiometricPrompt()
        }
    }

    fun startPaymentWithPassword(password: String, context: Context): Boolean {
        wallet?.secretStorage?.let {
            val mnemonic: String?
            try {
                val decryptData = AesEncryptionManager.decryptData(password, it)
                mnemonic = deserializeSecrets(String(decryptData!!))
            } catch (t: Throwable) {
                // Password wrong
                return false
            }

            if (mnemonic == null) {
                // deserialization error, corrupted db data
                return false
            }

            startPaymentWithMnemonicAsync(mnemonic, context)

            _lockInterface.postValue(true)

            return true
        }

        return false
    }

    fun startPaymentUserAuth(context: Context) {
        // we don't handle exceptions here by intention: we throw them back to the fragment which
        // will show a snackbar to give the user a hint what went wrong
        wallet?.secretStorage?.let {
            val mnemonic: String?

            val decryptData = AesEncryptionManager.decryptDataWithDeviceKey(it)
            mnemonic = deserializeSecrets(String(decryptData!!))

            startPaymentWithMnemonicAsync(mnemonic!!, context)

            _lockInterface.postValue(true)

        }
    }

    private fun startPaymentWithMnemonicAsync(mnemonic: String, context: Context) {
        viewModelScope.launch {
            val ergoTxResult: TransactionResult
            withContext(Dispatchers.IO) {
                ergoTxResult = sendErgoTx(
                    Address.create(receiverAddress), ergsToNanoErgs(amountToSend),
                    mnemonic, "", 0,
                    getPrefNodeUrl(context), getPrefExplorerApiUrl(context)
                )
            }
            _lockInterface.postValue(false)
            if (ergoTxResult.success) {
                NodeConnector.getInstance().invalidateCache()
                _txId.postValue(ergoTxResult.txId!!)
            }
            _paymentDoneLiveData.postValue(ergoTxResult)
        }
    }

    /**
     * @return list of tokens to choose from, that means available on the wallet and not already chosen
     */
    fun getTokensToChooseFrom(): List<WalletTokenDbEntity> {
        val choosenIdsAsString = tokensChosen.map { it.id.toString() }

        return tokensAvail.filter {
            !choosenIdsAsString.contains(it.tokenId)
        }
    }

    fun newTokenChoosen(tokenId: String) {
        tokensChosen.add(ErgoToken(tokenId, 0))
        _tokensChosenLiveData.postValue(tokensChosen)
    }

    fun removeToken(tokenId: String) {
        val size = tokensChosen.size
        tokensChosen.removeAll(tokensChosen.filter { it.id.toString().equals(tokenId) })
        if (tokensChosen.size != size) {
            _tokensChosenLiveData.postValue(tokensChosen)
        }
    }
}