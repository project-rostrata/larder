# Builds and runs the larder ingredient-parser sidecar -- see README.md for what this is.
# Single-stage: unlike the Kotlin backend's build/runtime split (which strips out the
# compiler), there's no analogous build tool to discard here -- pip installs run once and the
# resulting environment IS the runtime.
# Keep in step with PYTHON_IMAGE in docker/Dockerfile, which is what actually ships.
FROM python:3.14.7-slim-trixie

# Fixed, explicit path rather than relying on $HOME's default -- keeps the NLTK data written
# during the build step below findable at runtime regardless of which user the container runs
# as.
ENV NLTK_DATA=/usr/local/share/nltk_data

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-fetches the NLTK tagger data at BUILD time, not container startup -- the running
# container needs zero internet access. Downloaded explicitly to $NLTK_DATA rather than left to
# nltk.download()'s own default (which ignores the NLTK_DATA env var for its write location and
# always lands in the current user's home directory -- confirmed by testing, not assumed --
# which would silently break if this image ever adds a non-root USER later, since $NLTK_DATA is
# still checked on the *read* path, just not the write one). See README.md's verification
# section for how "no runtime internet access needed" is actually checked, not just assumed.
RUN python -c "import nltk; nltk.download('averaged_perceptron_tagger_eng', download_dir='${NLTK_DATA}', quiet=True)"
RUN python -c "import ingredient_parser"

COPY app.py .

ENV INGREDIENT_PARSER_PORT=8000
EXPOSE 8000

CMD ["python", "app.py"]
