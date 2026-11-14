package triplog

/** Default for human-managed records. Legacy classifier types remain readable. */
class ManualClassifier : PurposeClassifier {
    override fun classify(text: String) = Classification(Category.UNCLASSIFIED, "manual")
}
