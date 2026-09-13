package work.tokenpro.client;

import javax.swing.*;
import java.lang.reflect.*;
import java.util.*;

/** Headed UI regression: old multi-image state is normalized and clicks stay exclusive. */
public final class ModelPickerSingleImageIntegrationTest {
    public static void main(String[] args) throws Exception {
        PricedModel chat = new PricedModel("gpt-5.6-sol", "openai", "GPT", 16);
        PricedModel flare = new PricedModel("gpt-image-2.5-flare", "openai", "GPT「生图」", 65);
        PricedModel sunburst = new PricedModel("gpt-image-2.5-sunburst", "openai", "GPT「生图」", 65);
        PricedModel image2 = new PricedModel("gpt-image-2", "openai", "GPT「生图」", 65);
        SwingUtilities.invokeAndWait(() -> {
            ModelPickerDialog dialog = new ModelPickerDialog(null, "Codex", List.of(chat, flare, sunburst, image2),
                Set.of(ModelPickerDialog.id(flare), ModelPickerDialog.id(sunburst), ModelPickerDialog.id(image2)), ignored -> {});
            try {
                List<AbstractButton> choices = choices(dialog);
                require(selectedImages(choices).size() == 1, "legacy multi-image selection was not normalized");
                AbstractButton target = choices.stream().filter(choice -> model(choice).equals(flare)).findFirst().orElseThrow();
                if (!target.isSelected()) target.doClick();
                require(selectedImages(choices).equals(List.of(flare)), "click did not enforce one-to-one image selection");
            } finally { dialog.dispose(); }
        });
        System.out.println("Model picker keeps exactly one selected image model and switches it atomically.");
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractButton> choices(ModelPickerDialog dialog) {
        try {
            Field field = ModelPickerDialog.class.getDeclaredField("choices");
            field.setAccessible(true);
            return (List<AbstractButton>) field.get(dialog);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static PricedModel model(AbstractButton choice) {
        try {
            Method method = choice.getClass().getDeclaredMethod("model");
            method.setAccessible(true);
            return (PricedModel) method.invoke(choice);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static List<PricedModel> selectedImages(List<AbstractButton> choices) {
        return choices.stream().filter(AbstractButton::isSelected).map(ModelPickerSingleImageIntegrationTest::model)
            .filter(PricedModel::isImageGeneration).toList();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
