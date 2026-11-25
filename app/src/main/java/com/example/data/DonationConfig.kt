package com.example.data

/**
 * Represents a single direct cryptocurrency donation option displayed in the "Support Nepi" screen.
 *
 * SECURITY NOTE:
 * Only public receiving wallet addresses belong in this file.
 * Never store private keys, seed phrases, mnemonic recovery phrases, or wallet credentials anywhere
 * in this repository.
 */
data class CryptoDonationOption(
    val id: String,
    val currencyName: String,
    val ticker: String,
    val networkName: String,
    val address: String
) {
    val isConfigured: Boolean
        get() = address.isNotBlank()
}

/**
 * Centralized configuration for optional direct cryptocurrency donations.
 *
 * To update, add, or remove a donation receiving address later:
 * 1. Edit the entries in [allOptions] below.
 * 2. Keep each currency + network pair explicit so users never send funds over the wrong network.
 * 3. Any option with a blank address is automatically hidden by [configuredOptions].
 */
object DonationConfig {

    // Public receiving addresses (read-only donation destinations)
    private const val BTC_BITCOIN_ADDRESS = "bc1q94hhvl2el957z5z5y0tuh2umra5gze2p2uyz07"
    private const val EVM_ADDRESS = "0xcef301F37CBE1a97C8c9130d8401AE24da1072A2"
    private const val USDT_TRC20_ADDRESS = "TUUsKYeifSGDW8LUXhm1CabfxGSYbmjLvR"

    /**
     * Production cryptocurrency donation options.
     * Each option is displayed separately with its exact network label and its own QR code.
     */
    val allOptions: List<CryptoDonationOption> = listOf(
        CryptoDonationOption(
            id = "btc_bitcoin",
            currencyName = "Bitcoin",
            ticker = "BTC",
            networkName = "Bitcoin",
            address = BTC_BITCOIN_ADDRESS
        ),
        CryptoDonationOption(
            id = "bnb_bep20",
            currencyName = "BNB",
            ticker = "BNB",
            networkName = "BNB Smart Chain (BEP20)",
            address = EVM_ADDRESS
        ),
        CryptoDonationOption(
            id = "eth_erc20",
            currencyName = "Ethereum",
            ticker = "ETH",
            networkName = "Ethereum Mainnet (ERC20)",
            address = EVM_ADDRESS
        ),
        CryptoDonationOption(
            id = "usdt_erc20",
            currencyName = "Tether",
            ticker = "USDT",
            networkName = "Ethereum Mainnet (ERC20)",
            address = EVM_ADDRESS
        ),
        CryptoDonationOption(
            id = "usdt_trc20",
            currencyName = "Tether",
            ticker = "USDT",
            networkName = "TRON (TRC20)",
            address = USDT_TRC20_ADDRESS
        )
    )

    /**
     * Returns only options with a non-blank configured receiving address.
     */
    fun configuredOptions(
        options: List<CryptoDonationOption> = allOptions
    ): List<CryptoDonationOption> = options.filter { it.isConfigured }
}
